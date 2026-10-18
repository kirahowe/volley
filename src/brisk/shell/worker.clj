(ns brisk.shell.worker
  "Worker process: reads assignments on stdin, runs test namespaces with
  clojure.test, writes protocol events on the original stdout.

  Portable Clojure. It runs on babashka and on the JVM, so it uses no
  babashka.* namespaces.

  The protocol channel is the process's original stdout. At startup `-main`
  points `System/out` and the root `*out*` at stderr, so anything a test
  prints, even through `(.println System/out ...)`, arrives as stray output
  and can never interleave with a protocol line."
  (:require
   [brisk.protocol :as protocol]
   [clojure.test :as t]))

;; ---------------------------------------------------------------------------
;; Pure helpers

(defn duration-ms
  "Milliseconds between two `System/nanoTime` readings."
  [start-ns end-ns]
  (/ (- end-ns start-ns) 1e6))

(defn run-everything
  "A `decide` that selects every var. Used when `brisk.select` is absent."
  [_select _ns-sym _ns-meta _var-sym _var-meta]
  {:run? true :reason nil})

(defn test-vars-of
  "Interned vars of `ns-obj` that carry `:test`, ordered by name like
  Kaocha's `kaocha.type.ns/load`."
  [ns-obj]
  (->> (ns-interns ns-obj)
       (sort-by key)
       (map val)
       (filter (comp :test meta))))

(defn partition-vars
  "Ask `decide` (the 5-arity of `brisk.select/decide`) about each var, with
  the namespace's metadata `ns-meta`. Returns `{:run [var ...] :skipped
  [[var reason] ...]}`, both in input order."
  [decide select ns-sym ns-meta vars]
  (reduce (fn [acc v]
            (let [m (meta v)
                  {:keys [run? reason]} (decide select ns-sym ns-meta (:name m) m)]
              (if run?
                (update acc :run conj v)
                (update acc :skipped conj [v reason]))))
          {:run [] :skipped []}
          vars))

(defn- throwable? [x] (instance? Throwable x))

(defn- same-file?
  "True when report file `rf` (often just a base name, or `NO_SOURCE_PATH`
  on babashka) can be trusted to mean the var's file `vf`."
  [rf vf]
  (and (string? rf) (string? vf)
       (or (.endsWith ^String vf ^String rf) (.endsWith ^String rf ^String vf))))

(defn- location
  "Where an assertion happened. A `:fail` report carries the `is` call site
  (`NO_SOURCE_PATH` on babashka). An `:error` report on the JVM carries the
  throw site, possibly inside the JDK, so fall back to the var's own."
  [m vm]
  (let [rf (:file m)]
    (cond
      (same-file? rf (:file vm)) {:file (:file vm) :line (or (:line m) (:line vm))}
      (and (= :fail (:type m)) (string? rf) (not= "NO_SOURCE_PATH" rf))
      {:file rf :line (:line m)}
      (and (= :fail (:type m)) (= "NO_SOURCE_PATH" rf))
      {:file (:file vm) :line (or (:line m) (:line vm))}
      :else {:file (:file vm) :line (:line vm)})))

(defn assertion-event
  "Event for a failing or erroring clojure.test report map `m`, raised
  inside var `v` (nil outside any var) with `contexts` outermost first."
  [ns-sym v contexts m]
  (let [actual (:actual m)
        loc (location m (meta v))
        error? (and (= :error (:type m)) (throwable? actual))]
    {:event :assertion
     :ns ns-sym
     :var (some-> v meta :name)
     :type (:type m)
     :message (some-> (:message m) str)
     :contexts (vec (map str contexts))
     :expected (protocol/encode-value (:expected m))
     :actual (if error?
               {:str (str (.getName (class actual)) ": " (ex-message actual))}
               (protocol/encode-value actual))
     :file (:file loc)
     :line (:line loc)
     :error (when error? (protocol/encode-error (Throwable->map actual)))}))

;; ---------------------------------------------------------------------------
;; Running a namespace

(defn- empty->nil [s] (when-not (= "" s) s))

(defn- open-capture
  "One sink for everything a test prints: `*out*`, `*err*`, `System/out`
  and `System/err` all write to the same buffer, in order, as
  `kaocha.plugin.capture-output` does."
  []
  (let [bytes (java.io.ByteArrayOutputStream.)
        stream (java.io.PrintStream. bytes true "UTF-8")]
    {:bytes bytes
     :stream stream
     :writer (java.io.OutputStreamWriter. stream "UTF-8")}))

(defn- capture-text
  "Everything captured so far, or nil when nothing was printed."
  [{:keys [bytes stream ^java.io.Writer writer]}]
  (.flush writer)
  (.flush ^java.io.PrintStream stream)
  (empty->nil (.toString ^java.io.ByteArrayOutputStream bytes "UTF-8")))

(defn- start-var!
  "Open the accumulator for var `v`, redirecting output when capturing."
  [acc emit! ns-sym capture? v]
  (let [capture (when capture? (open-capture))
        m (meta v)]
    (reset! acc {:var v :pass 0 :fail 0 :error 0
                 :t0 (System/nanoTime) :capture capture
                 :saved-out *out* :saved-err *err*
                 :saved-system-out System/out :saved-system-err System/err})
    (when capture
      (set! *out* (:writer capture))
      (set! *err* (:writer capture))
      (System/setOut (:stream capture))
      (System/setErr (:stream capture)))
    (emit! {:event :var/start :ns ns-sym :var (:name m)
            :file (:file m) :line (:line m)})))

(defn- end-var!
  "Close the open var (if any), restoring output, and emit `:var/end`."
  [acc emit! ns-sym]
  (when-let [{:keys [var pass fail error t0 capture saved-out saved-err
                     saved-system-out saved-system-err]} @acc]
    (when capture
      (set! *out* saved-out)
      (set! *err* saved-err)
      (System/setOut saved-system-out)
      (System/setErr saved-system-err))
    (reset! acc nil)
    (emit! {:event :var/end :ns ns-sym :var (:name (meta var))
            :pass pass :fail fail :error error
            :duration-ms (duration-ms t0 (System/nanoTime))
            :output (some-> capture capture-text)})))

(defn- count-and-emit!
  "Count a `:pass`/`:fail`/`:error` against the open var and emit the
  assertion event for failures."
  [acc emit! ns-sym m]
  (let [kind (:type m)
        open (:var @acc)]
    (when open (swap! acc update kind inc))
    (when (#{:fail :error} kind)
      (emit! (assertion-event ns-sym open (reverse t/*testing-contexts*) m)))))

(defn- make-report
  "A `clojure.test/report` replacement. clojure.test reports through a
  callback with no return channel, so the open var's counters live in `acc`,
  an atom local to one `run-vars!` call."
  [acc emit! ns-sym capture?]
  (fn [m]
    (case (:type m)
      :begin-test-var (start-var! acc emit! ns-sym capture? (:var m))
      :end-test-var (end-var! acc emit! ns-sym)
      (:pass :fail :error) (count-and-emit! acc emit! ns-sym m)
      nil)))

(defn- run-vars!
  "Run `vars` through `clojure.test/test-vars`, so `:once` and `:each`
  fixtures and `testing` contexts behave exactly as in `run-tests`. Returns
  nil, or the Throwable that escaped (a failing fixture, for instance)."
  [emit! ns-sym capture? vars]
  (let [acc (atom nil)]
    (try
      (binding [t/report (make-report acc emit! ns-sym capture?)
                *out* *out*
                *err* *err*]
        (try
          (t/test-vars vars)
          nil
          (catch Throwable e
            (end-var! acc emit! ns-sym)
            e)))
      (catch Throwable e e))))

(defn- error-event [ns-sym run-id e]
  {:event :ns/error :ns ns-sym :run-id run-id
   :error (protocol/encode-error (Throwable->map e))})

(defn- ns-metadata
  "The loaded namespace's metadata, over the static copy the parent read
  from the ns form (Kaocha reads the real one, `kaocha.type.ns`)."
  [select ns-obj]
  (merge (:ns-meta select) (meta ns-obj)))

(defn run-assignment!
  "Run one `{:op :run}` message. `emit!` receives each event map. `decide` is
  `(fn [select ns-sym ns-meta var-sym var-meta] {:run? bool :reason kw})`,
  normally `brisk.select/decide`; it gets the namespace's real metadata.

  Emits `:ns/start`, then `:ns/error` if loading or a fixture fails, the
  `:var/*` and `:assertion` events, and always a final `:ns/end`. Does not
  exit the JVM and does not throw."
  [emit! decide {:keys [ns run-id select capture-output?]}]
  (let [t0 (System/nanoTime)
        with-run (fn [ev] (assoc ev :run-id run-id))]
    (emit! {:event :ns/start :ns ns :run-id run-id})
    (let [load-error (try (require ns) nil (catch Throwable e e))
          run-error
          (if load-error
            load-error
            (let [ns-obj (find-ns ns)
                  {:keys [run skipped]}
                  (partition-vars decide select ns (ns-metadata select ns-obj) (test-vars-of ns-obj))]
              (doseq [[v reason] skipped]
                (emit! {:event :var/skipped :ns ns :var (:name (meta v))
                        :reason reason}))
              (run-vars! emit! ns (boolean capture-output?) run)))]
      (when run-error (emit! (error-event ns run-id run-error)))
      (emit! (with-run {:event :ns/end :ns ns
                        :duration-ms (duration-ms t0 (System/nanoTime))})))))

;; ---------------------------------------------------------------------------
;; Process entry point

(defn- resolve-decide []
  (or (try (requiring-resolve 'brisk.select/decide)
           (catch Throwable _ nil))
      run-everything))

(defn- utf8-writer [^java.io.OutputStream out]
  (java.io.OutputStreamWriter. out "UTF-8"))

(defn- make-emit
  "Emit function writing whole protocol lines to `out`, atomically."
  [^java.io.Writer out]
  (fn [event]
    (let [line (str (protocol/encode-event event) "\n")]
      (locking out
        (.write out line)
        (.flush out)))))

(defn- handle-line!
  "Handle one stdin line. Returns :shutdown to stop, else nil."
  [emit! decide line]
  (let [{:keys [kind command error]} (protocol/decode-command line)]
    (case kind
      :command (case (:op command)
                 :run (do (run-assignment! emit! decide command) nil)
                 :shutdown :shutdown
                 (binding [*out* *err*] (println "brisk worker: unknown op" (:op command))))
      :invalid (binding [*out* *err*]
                 (println "brisk worker: bad message:" error)))))

(defn -main
  "Worker entry point. Protocol events go to the original stdout; everything
  else a test prints goes to stderr."
  [& _]
  (let [proto-out (utf8-writer System/out)
        stray (java.io.PrintStream. System/err true "UTF-8")
        stray-writer (utf8-writer stray)
        emit! (make-emit proto-out)
        decide (resolve-decide)
        in (java.io.BufferedReader.
            (java.io.InputStreamReader. System/in "UTF-8"))]
    (System/setOut stray)
    (alter-var-root #'*out* (constantly stray-writer))
    (alter-var-root #'*err* (constantly stray-writer))
    (emit! {:event :worker/ready})
    (loop []
      (when-let [line (.readLine in)]
        (when-not (= :shutdown
                     (binding [*in* (java.io.BufferedReader. (java.io.StringReader. ""))]
                       (handle-line! emit! decide line)))
          (.flush stray-writer)
          (recur))))
    (.flush stray-writer)
    (shutdown-agents)
    (System/exit 0)))

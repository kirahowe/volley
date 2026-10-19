(ns volley.shell.worker
  "Worker process: reads assignments on stdin, runs test namespaces with
  clojure.test, writes protocol events on the original stdout.

  Portable Clojure. It runs on babashka and on the JVM, so it uses no
  babashka.* namespaces.

  The protocol channel is the process's original stdout. At startup `-main`
  points `System/out` and the root `*out*` at stderr, so anything a test
  prints, even through `(.println System/out ...)`, arrives as stray output
  and can never interleave with a protocol line."
  (:require
   [volley.filters :as filters]
   [volley.order :as order]
   [volley.protocol :as protocol]
   [volley.shell.hooks :as hooks]
   [clojure.java.io :as io]
   [clojure.test :as t]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; Pure helpers

(defn duration-ms
  "Milliseconds between two `System/nanoTime` readings."
  [start-ns end-ns]
  (/ (- end-ns start-ns) 1e6))

(defn run-everything
  "A `decide` that selects every var, for running a namespace whole."
  [_select _ns-sym _ns-meta _var-sym _var-meta]
  {:run? true :reason nil})

(defn test-vars-of
  "Interned vars of `ns-obj` that carry `:test`, ordered by name like
  Kaocha's `kaocha.type.ns/load`, or with a run `seed` in the random order
  `volley.order` picks for the namespace."
  ([ns-obj] (test-vars-of ns-obj nil))
  ([ns-obj seed]
   (let [tests (into (sorted-map) (filter (comp :test meta val)) (ns-interns ns-obj))]
     (if seed
       (map tests (order/shuffle-names seed (ns-name ns-obj) (keys tests)))
       (vals tests)))))

(defn partition-vars
  "Ask `decide` (the 5-arity of `volley.filters/decide`) about each var, with
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

(defn safely
  "Call `f` with the print vars bound to `volley.protocol/safe-print-bindings`.
  Events are printed inside the user's dynamic scope (a report from an `is`,
  a fixture that bound `*print-length*`, a namespace that `set!` it at load
  time), so without this a protocol line could come out truncated or in a
  form EDN cannot read."
  [f]
  (with-bindings* protocol/safe-print-bindings f))

(defn- bb? [] (some? (System/getProperty "babashka.version")))

(defn- same-file?
  "True when report file `rf` (often just a base name, or `NO_SOURCE_PATH`
  on babashka) can be trusted to mean the var's file `vf`."
  [rf vf]
  (and (string? rf) (string? vf)
       (or (.endsWith ^String vf ^String rf) (.endsWith ^String rf ^String vf))))

(defn- bare-name? [^String f]
  (not (or (.contains f "/") (.contains f java.io.File/separator))))

(defn project-file?
  "True when report file `rf` can be shown as the place of a failure: a
  file under directory `dir` (relative to it or absolute), or, on the JVM
  (`bb?` false), a bare file name from a stack frame of compiled code. On
  babashka a bare name or a path outside the project comes from the
  interpreter (a file such as `reflector.cljc` that a stack-walking
  assertion like test.check's picked up), not from the user's code."
  [dir bb? rf]
  (and (string? rf)
       (not= "NO_SOURCE_PATH" rf)
       (let [f (java.io.File. ^String rf)
             abs (if (.isAbsolute f) f (java.io.File. ^String dir ^String rf))]
         (or (and (.isFile abs)
                  (.startsWith (.getCanonicalPath abs)
                               (str (.getCanonicalPath (java.io.File. ^String dir)) java.io.File/separator)))
             (and (not bb?) (bare-name? rf))))))

(defn- location
  "Where an assertion happened. A `:fail` report carries the `is` call site
  (`NO_SOURCE_PATH` on babashka; on either runtime, test.check reports the
  first stack frame it finds, which on babashka is an interpreter file). An
  `:error` report on the JVM carries the throw site, possibly inside the
  JDK. Whenever the reported file is not the project's, fall back to the
  var's own file and line."
  [m vm]
  (let [rf (:file m)]
    (cond
      (same-file? rf (:file vm)) {:file (:file vm) :line (or (:line m) (:line vm))}
      (and (= :fail (:type m)) (project-file? (System/getProperty "user.dir") (bb?) rf))
      {:file rf :line (:line m)}
      (and (= :fail (:type m)) (= "NO_SOURCE_PATH" rf))
      {:file (:file vm) :line (or (:line m) (:line vm))}
      :else {:file (:file vm) :line (:line vm)})))

(def ^:private sci-stacktrace
  "`sci.core/stacktrace` on babashka, else nil."
  (delay (when (bb?)
           (try (requiring-resolve 'sci.core/stacktrace)
                (catch Throwable _ nil)))))

(defn- chain
  "`t` and its causes, outermost first."
  [t]
  (take-while some? (iterate ex-cause t)))

(defn- without-wrappers
  "`Throwable->map` data `tm` of `e` with the interpreter's wrappers left
  out of `:via` (they repeat their cause's message with the call stack in
  their data) and messages that quote a wrapper rebuilt
  (`volley.shell.hooks/message`)."
  [tm e]
  (let [ts (chain e)]
    (if (or (not= (count ts) (count (:via tm))) (not-any? hooks/sci-wrapper? ts))
      tm
      (assoc tm :via (into [] (keep (fn [[t v]]
                                      (when-not (hooks/sci-wrapper? t)
                                        (assoc v :message (hooks/message t)))))
                           (map vector ts (:via tm)))))))

(defn encode-throwable
  "Wire form of a Throwable a test raised. On babashka the JVM stack trace
  is nothing but interpreter frames, so the trace comes from the
  interpreter's own call stack (`sci.core/stacktrace`), which names the
  user's functions with file and line: `e`'s, or else that of the first
  wrapper in its cause chain that names one of them. The interpreter's wrappers are left out of
  the cause chain wherever they are. The JVM keeps its stack trace."
  [^Throwable e]
  (let [enc (protocol/encode-error (without-wrappers (Throwable->map e) e))
        st @sci-stacktrace
        trace-of (fn [t] (when st
                           (seq (protocol/interpreter-trace (try (st t) (catch Throwable _ nil))
                                                            (System/getProperty "user.dir")))))
        trace (or (trace-of e) (some #(when (hooks/sci-wrapper? %) (trace-of %)) (rest (chain e))))]
    (cond-> enc (seq trace) (assoc :trace trace))))

(defn relative-path
  "`file` relative to directory `dir` when it lies inside it, else as is."
  [dir file]
  (let [prefix (str dir java.io.File/separator)]
    (if (and (string? file) (string? dir) (.startsWith ^String file prefix))
      (subs file (count prefix))
      file)))

(defn- classpath-file
  "The file system path of `file` when it names a source file on the
  classpath, as the JVM's var metadata does (`p/a_test.clj`), else nil."
  [file]
  (when-let [url (and (string? file) (not (.isAbsolute (java.io.File. ^String file)))
                      (io/resource file))]
    (when (= "file" (.getProtocol ^java.net.URL url))
      (.getCanonicalPath (java.io.File. (.toURI ^java.net.URL url))))))

(defn project-path
  "`file` as the project sees it, relative to directory `dir`: babashka's
  var metadata already holds `test/p/a_test.clj`; the JVM's holds the
  classpath-relative `p/a_test.clj`, which is looked up on the classpath.
  Anything else comes back as it was."
  [dir file]
  (let [abs? (and (string? file) (.isAbsolute (java.io.File. ^String file)))]
    (cond
      (or (not (string? file)) abs?) (relative-path dir file)
      (.isFile (java.io.File. ^String dir ^String file)) file
      :else (if-let [found (classpath-file file)]
              (relative-path (.getCanonicalPath (java.io.File. ^String dir)) found)
              file))))

(defn- rel [file] (project-path (System/getProperty "user.dir") file))

(def zero-assertions-message
  "What Kaocha reports for a test var that made no assertion
  (`kaocha.type.var`, report type `:kaocha.type.var/zero-assertions`)."
  "Test ran without assertions. Did you forget an (is ...)?")

(defn assertion-event
  "Event for a failing or erroring clojure.test report map `m`, raised
  inside var `v` (nil outside any var) with `contexts` outermost first.
  Kaocha's zero-assertions report is a failure with its message and no
  expected or actual value."
  [ns-sym v contexts m]
  (let [raw (:actual m)
        actual (cond-> raw (throwable? raw) hooks/unwrap)
        loc (location m (meta v))
        error? (and (= :error (:type m)) (throwable? actual))
        check (when (= :fail (:type m)) (protocol/encode-check actual))]
    (cond-> {:event :assertion
             :ns ns-sym
             :var (some-> v meta :name)
             :type (:type m)
             :message (some-> (:message m) str)
             :contexts (vec (map str contexts))
             :expected (when-not (::zero? m) (protocol/encode-value (:expected m)))
             :actual (cond
                       (::zero? m) nil
                       error? {:str (str (.getName (class actual)) ": " (hooks/message actual))}
                       :else (protocol/encode-value actual))
             :file (rel (:file loc))
             :line (:line loc)
             :error (when error? (encode-throwable raw))}
      check (assoc :check check))))

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

(def ^:private stop-requested
  "Set as soon as the parent's `:shutdown` arrives, even while a namespace
  is running; that namespace then runs no more vars, so the worker can run
  its shutdown hooks before the parent gives up and kills it."
  (atom false))

(def ^:private live-capture
  "The capture buffer of the test running right now, so that a test that
  exits the process does not take its output with it (see `-main`)."
  (atom nil))

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
      (reset! live-capture capture)
      (set! *out* (:writer capture))
      (set! *err* (:writer capture))
      (System/setOut (:stream capture))
      (System/setErr (:stream capture)))
    (emit! {:event :var/start :ns ns-sym :var (:name m)
            :file (rel (:file m)) :line (:line m)})))

(defn- end-var!
  "Close the open var (if any), restoring output, and emit `:var/end`. The
  accumulator keeps the ended var's counts under `:ended`."
  [acc emit! ns-sym]
  (when-let [{:keys [var pass fail error t0 capture saved-out saved-err
                     saved-system-out saved-system-err]} (when (:var @acc) @acc)]
    (when capture
      (reset! live-capture nil)
      (set! *out* saved-out)
      (set! *err* saved-err)
      (System/setOut saved-system-out)
      (System/setErr saved-system-err))
    (reset! acc {:ended {:pass pass :fail fail :error error}})
    (emit! {:event :var/end :ns ns-sym :var (:name (meta var))
            :pass pass :fail fail :error error
            :duration-ms (duration-ms t0 (System/nanoTime))
            :output (some-> capture capture-text)})))

(defn- count-and-emit!
  "Count a `:pass`/`:fail`/`:error` against the open var, or, outside any
  var (in a `:once` fixture), count a pass in `(:outside @progress)`, and
  emit the assertion event for failures."
  [acc progress emit! ns-sym m]
  (let [kind (:type m)
        open (:var @acc)]
    (cond
      open (swap! acc update kind inc)
      (= :pass kind) (vswap! progress update :outside inc))
    (when (#{:fail :error} kind)
      (emit! (assertion-event ns-sym open (reverse t/*testing-contexts*) m)))))

(defn- make-report
  "A `clojure.test/report` replacement. clojure.test reports through a
  callback with no return channel, so the open var's counters live in `acc`,
  an atom local to one `run-vars!` call, and passes outside any var in
  `progress`. A var opens before its `:each` fixtures run and closes after
  them (`run-vars!`), so what a fixture asserts counts for the var it
  wraps, as in Kaocha; `:begin-test-var` and `:end-test-var` are ignored.
  It runs inside the test's dynamic scope, so values are printed with
  `safely`."
  [acc progress emit! ns-sym]
  (fn [m]
    (safely
     #(case (:type m)
        (:pass :fail :error) (count-and-emit! acc progress emit! ns-sym m)
        :kaocha.type.var/zero-assertions
        (count-and-emit! acc progress emit! ns-sym
                         (assoc m :type :fail :message zero-assertions-message ::zero? true))
        nil))))

(defn- check-assertions!
  "Kaocha fails a test var that made no assertion: report it as Kaocha's
  `kaocha.type.var/-run` does, after the var and its `:each` fixtures
  ran, through `clojure.test/report` so `pre-report` hooks see it."
  [acc]
  (let [{:keys [var pass fail error]} @acc]
    (when (and var (zero? (+ pass fail error)))
      (t/do-report {:type :kaocha.type.var/zero-assertions}))))

(defn- test-vars!
  "What `clojure.test/test-vars` does for the vars of one namespace: the
  `:once` fixtures around all of them, the `:each` fixtures around each
  `test-var`. `(run-var v test!)` runs one var, calling `test!` to run it
  inside the `:each` fixtures, and returns its result; after each var
  `(stop? result)` is asked whether to skip the rest (the `:once` fixtures
  still finish), and no var starts once `stop-requested` is set. Each
  result is added to `(:results @progress)`, since a `:once` fixture does
  not hand back what the tests return. Returns the results."
  [ns-obj vars run-var stop? progress]
  (let [m (meta ns-obj)
        once (t/join-fixtures (:clojure.test/once-fixtures m))
        each (t/join-fixtures (:clojure.test/each-fixtures m))]
    (once (fn []
            (reduce (fn [_ v]
                      (if @stop-requested
                        (reduced nil)
                        (let [r (run-var v #(each (fn [] (t/test-var v))))]
                          (vswap! progress update :results conj r)
                          (when (stop? r) (reduced nil)))))
                    nil
                    vars)))
    (:results @progress)))

(defn- failed? [{:kaocha.result/keys [fail error]}]
  (pos? (+ (or fail 0) (or error 0))))

(defn- run-vars!
  "Run `vars` of `ns-obj` with fixtures and `testing` contexts as in
  `run-tests`, and the per-namespace and per-var hooks of `hs`. With
  `fail-fast?` the rest of the vars are not run once one fails, as Kaocha
  stops at the first failure. Returns `[hs error]`: error is nil, or the
  Throwable that escaped (a failing fixture or hook, for instance). The
  vars it kept from running are sent as skipped with reason
  `:fixture-failed`, so none of them vanishes. The third element is the
  number of passing assertions made outside any var.

  clojure.test reports through a callback and fixtures return nothing of
  the tests they wrap, so two mutable cells carry what the run learns out
  of them: `acc`, the open var's counters, which the report callback
  updates, and `progress`, the vars reached, their results and the passes
  outside any var, which must survive a fixture that throws."
  [hs emit! suite ns-sym ns-obj {:keys [capture? fail-fast?]} vars]
  (let [acc (atom nil)
        progress (volatile! {:reached #{} :results [] :outside 0})
        outside #(:outside @progress)
        counts #(:ended @acc)
        run-var (fn [v test!]
                  (reset! acc nil)
                  (vswap! progress update :reached conj v)
                  (let [test! (fn []
                                (start-var! acc emit! ns-sym capture? v)
                                (try (test!)
                                     (check-assertions! acc)
                                     (finally (safely #(end-var! acc emit! ns-sym)))))
                        r (hooks/run-var hs ns-sym v test! counts)]
                    (cond
                      (:kaocha.testable/skip r)
                      (emit! {:event :var/skipped :ns ns-sym :var (:name (meta v)) :reason :pre-test-hook})
                      ;; the var never opened, so a wrap-run hook did not call run
                      (nil? (counts))
                      (emit! {:event :worker/warning
                              :message (str "the wrap-run hook did not call run for " ns-sym "/" (:name (meta v))
                                            ", so it did not run (Kaocha does not run it either)")}))
                    r))
        stop? (fn [r] (and fail-fast? (failed? r)))
        abandon! (fn []
                   (doseq [v vars :when (not (contains? (:reached @progress) v))]
                     (emit! {:event :var/skipped :ns ns-sym :var (:name (meta v)) :reason :fixture-failed})))]
    (try
      (binding [t/report (hooks/report-fn hs (make-report acc progress emit! ns-sym))
                *out* *out*
                *err* *err*]
        (try
          (let [[hs _] (hooks/run-ns hs suite ns-sym ns-obj #(test-vars! ns-obj vars run-var stop? progress))]
            ;; with vars to run, a run that reached none, with no stop
            ;; requested, was never started
            (when (and (seq vars) (empty? (:reached @progress)) (not @stop-requested))
              (emit! {:event :worker/warning
                      :message (str "the wrap-run hook did not call run for " ns-sym
                                    ", so its " (count vars) " test" (when (next vars) "s")
                                    " did not run (Kaocha does not run " (if (next vars) "them" "it") " either)")}))
            [hs nil (outside)])
          (catch ^:sci/error Throwable e
            (safely #(end-var! acc emit! ns-sym))
            (abandon!)
            [hs e (outside)])))
      (catch ^:sci/error Throwable e (abandon!) [hs e (outside)]))))

(defn- hook-of
  "`{:kind kw :entry entry}` when `e` (or what it wraps) is the error of a
  hook (`volley.shell.hooks`), else nil."
  [e]
  (let [{:keys [hook entry]} (ex-data (hooks/unwrap e))]
    (when (keyword? hook) {:kind hook :entry entry})))

(defn- error-event
  "`phase` is `:setup` for a worker setup or run-level hook that failed,
  `:load` for a failed require (or load hook) and `:run` for an error that
  escaped the tests themselves (a fixture or hook, typically). `:hook`
  names the hook when one threw."
  [ns-sym run-id phase e]
  (cond-> {:event :ns/error :ns ns-sym :run-id run-id :phase phase
           :error (safely #(encode-throwable e))}
    (hook-of e) (assoc :hook (hook-of e))))

(defn- ns-metadata
  "The loaded namespace's metadata, over the static copy the parent read
  from the ns form (Kaocha reads the real one, `kaocha.type.ns`)."
  [select ns-obj]
  (merge (:ns-meta select) (meta ns-obj)))

(defn- load-ns
  "Require `ns` between its load hooks, which see its vars in the run
  order of `seed`. Returns `[hs error]`."
  [hs suite ns seed]
  (try
    (hooks/pre-load-ns hs ns)
    (require ns)
    (let [ns-obj (find-ns ns)]
      [(hooks/post-load-ns hs suite ns ns-obj (test-vars-of ns-obj seed)) nil])
    (catch Throwable e [hs e])))

(defn- preload-suite!
  "Set up `suite` (its worker setup and load hooks, as before any of its
  namespaces loads), then require `nses`. A namespace that fails to load
  is warned about; its own run reports the error. Returns the next hook
  state; after a setup error nothing more is loaded."
  [hs emit! suite nses]
  (let [hs (hooks/setup hs suite)]
    (when-not (hooks/setup-error hs)
      (doseq [ns nses]
        (try (require ns)
             (catch Throwable e
               (emit! {:event :worker/warning
                       :message (str "--preload could not load " ns ": " (.getName (class e))
                                     (when-let [m (ex-message e)] (str ": " m)))})))))
    hs))

(defn- preload!
  "Require every namespace in `preload` (`[{:ns sym :suite id}]`, in plan
  order) once per worker, before the first test runs (`--preload`, or
  focus-meta keys only the loaded tests can settle). Each suite is set up
  before its namespaces load, as Kaocha loads suite by suite. Returns the
  next state."
  [hs emit! preload]
  (if (or (empty? preload) (::preloaded? hs))
    hs
    (-> (reduce (fn [hs entries]
                  (if (hooks/setup-error hs)
                    (reduced hs)
                    (preload-suite! hs emit! (:suite (first entries)) (map :ns entries))))
                hs
                (partition-by :suite preload))
        (assoc ::preloaded? true))))

(defn- loaded-metas
  "Metadata of the namespaces in `nses` that are loaded and of their test
  vars, for `volley.filters/used-meta`."
  [nses]
  (for [ns nses
        :let [ns-obj (find-ns ns)]
        :when ns-obj
        m (cons (meta ns-obj) (map meta (test-vars-of ns-obj)))]
    m))

(defn- settle-select
  "The select spec with its unresolved focus-meta keys settled against
  every preloaded test. Each dropped key is warned about once per worker,
  as Kaocha warns about it. Returns `[hs select]`."
  [hs emit! select preload]
  (if (seq (:resolve-focus-meta select))
    (let [{select :select dropped :dropped}
          (filters/resolve-focus-meta select (filters/used-meta (loaded-metas (map :ns preload))))
          new (remove (::warned hs #{}) dropped)]
      (doseq [k new]
        (emit! {:event :worker/warning :message (filters/missing-meta-warning k)}))
      [(update hs ::warned (fnil into #{}) new) select])
    [hs select]))

(defn- run-loaded
  "Run the vars of loaded namespace `ns`. Returns `[hs error phase pass]`,
  `pass` counting the passing assertions outside any var."
  [hs emit! decide {:keys [ns capture-output? fail-fast? suite preload] :as cmd}]
  (let [hs (hooks/before-run hs suite)]
    (if-let [e (hooks/setup-error hs)]
      [hs e :setup]
      (let [ns-obj (find-ns ns)
            [hs select] (settle-select hs emit! (:select cmd) preload)
            select (filters/prepare select)
            ns-meta (ns-metadata select ns-obj)
            {:keys [run skipped] :as parts}
            (partition-vars decide select ns ns-meta (test-vars-of ns-obj (:seed cmd)))]
        (doseq [[v reason] skipped]
          (emit! {:event :var/skipped :ns ns :var (:name (meta v)) :reason reason}))
        (if (filters/runs-namespace? ns-meta parts)
          (let [[hs e pass] (run-vars! hs emit! suite ns ns-obj {:capture? (boolean capture-output?)
                                                                :fail-fast? (boolean fail-fast?)}
                                       run)]
            [hs e :run pass])
          [hs nil :run 0])))))

(defn- binding-map
  "Kaocha's `:kaocha/bindings` (`{var-sym value}`) as a var -> value map for
  `with-bindings`, loading each var's namespace as `kaocha.config/binding-map`
  does. Throws when a var does not exist, which makes the namespace a setup
  error, as Kaocha fails the run then."
  [bindings]
  (into {} (map (fn [[sym v]]
                  [(or (requiring-resolve sym)
                       (throw (ex-info (str ":kaocha/bindings: no such var " sym) {:var sym})))
                   v]))
        bindings))

(defn- bindings-of
  "`[bmap error]`: `cmd`'s `:kaocha/bindings` as a `with-bindings` map, or
  why they could not be made."
  [cmd]
  (try [(binding-map (:bindings cmd)) nil]
       (catch Throwable e [nil e])))

(defn- run-bound
  "The part of `run-assignment!` inside `:kaocha/bindings`: setup, load,
  run, and the namespace's terminal events. `bind-error` is why the
  bindings could not be made, which stops the namespace like a failed
  setup."
  [emit! decide hs {:keys [ns run-id suite] :as cmd} t0 bind-error]
  (let [hs (hooks/setup hs suite)
        [hs error phase pass] (cond
                                bind-error [hs bind-error :setup]
                                (hooks/setup-error hs) [hs (hooks/setup-error hs) :setup]
                                :else (let [hs (preload! hs emit! (:preload cmd))
                                            [hs e] (load-ns hs suite ns (:seed cmd))]
                                        (if e [hs e :load] (run-loaded hs emit! decide cmd))))]
    (when error
      (emit! (error-event ns run-id phase error)))
    (emit! (cond-> {:event :ns/end :ns ns :run-id run-id
                    :duration-ms (duration-ms t0 (System/nanoTime))}
             (and pass (pos? pass)) (assoc :pass pass)))
    hs))

(defn run-assignment!
  "Run one `{:op :run}` message. `emit!` receives each event map. `decide` is
  `(fn [select ns-sym ns-meta var-sym var-meta] {:run? bool :reason kw})`,
  normally `volley.filters/decide`; it gets the namespace's real metadata.
  `hs` is the worker's hook state (`volley.shell.hooks/init`); the 3-arity
  runs without hooks. Returns the next hook state.

  Emits `:ns/start`, then `:ns/error` if setup, loading or a fixture
  fails, the `:var/*` and `:assertion` events, and always a final
  `:ns/end`. With `:fail-fast?` the vars after the first failing one are
  not run and get no events. Does not exit the JVM and does not throw."
  ([emit! decide cmd] (run-assignment! emit! decide (hooks/init nil) cmd))
  ([emit! decide hs {:keys [ns run-id] :as cmd}]
   (let [t0 (System/nanoTime)]
     (emit! {:event :ns/start :ns ns :run-id run-id})
     (let [[bmap bind-error] (bindings-of cmd)]
       (with-bindings* (or bmap {})
         #(run-bound emit! decide hs cmd t0 bind-error))))))

;; ---------------------------------------------------------------------------
;; Process entry point

(defn- utf8-writer [^java.io.OutputStream out]
  (java.io.OutputStreamWriter. out "UTF-8"))

(defn- make-emit
  "Emit function writing whole protocol lines with `prefix` to `out`,
  atomically, each framed by newlines (`volley.protocol/frame-event`)."
  [^java.io.Writer out prefix]
  (fn [event]
    (let [^String line (safely #(protocol/frame-event prefix event))]
      (locking out
        (.write out line)
        (.flush out)))))

(defn- report-hook-errors!
  "Errors of shutdown hooks (`post-run`, a suite's `post-test`) are sent as
  `:worker/error` events; there is no namespace to attach them to."
  [emit! errors]
  (doseq [e errors]
    (emit! (cond-> {:event :worker/error :phase :shutdown :error (safely #(encode-throwable e))}
             (hook-of e) (assoc :hook (hook-of e))))))

(defn- run-command!
  "Run `{:op :run}` command `cmd`. The first one of a suite runs inside the
  suite's `wrap-run` hooks, followed there by the rest of the worker's
  commands (`(more hs)`, which returns the final state), so the suite is
  wrapped once per worker; that returns the final state as `(reduced
  hs)`. Returns the next state."
  [emit! decide more hs {:keys [suite] :as cmd}]
  ;; bmap is nil when the bindings cannot be made: run-assignment! reports it
  (let [[bmap] (bindings-of cmd)]
    (if-not (and bmap (hooks/wrap-suite? hs suite))
      (run-assignment! emit! decide hs cmd)
      ;; inside :kaocha/bindings, as Kaocha's api/run binds the whole run
      (let [[hs ran?] (with-bindings* bmap
                        #(hooks/wrap-suite (hooks/setup hs suite) suite
                                           (fn [hs] (more (run-assignment! emit! decide hs cmd)))))]
        (if ran?
          (reduced hs)
          (run-assignment! emit! decide hs cmd))))))

(defn- handle-line!
  "Handle one stdin line with hook state `hs`; `more` serves the commands
  after it (see `run-command!`). Returns the next state, `(reduced hs)`
  when `more` already served the rest, or :shutdown to stop."
  [emit! decide more hs line]
  (let [{:keys [kind command error]} (protocol/decode-command line)]
    (case kind
      :command (case (:op command)
                 :run (run-command! emit! decide more hs command)
                 :configure (hooks/init command)
                 :shutdown :shutdown
                 (do (binding [*out* *err*] (println "volley worker: unknown op" (:op command))) hs))
      :invalid (do (binding [*out* *err*] (println "volley worker: bad message:" error)) hs))))

(defn- read-commands!
  "Put each line of `in` on `lines`, then `::eof`. Runs on its own thread so
  that a `:shutdown` is seen while a namespace runs: it sets
  `stop-requested` at once."
  [^java.io.BufferedReader in ^java.util.concurrent.BlockingQueue lines]
  (loop []
    (if-let [line (try (.readLine in) (catch java.io.IOException _ nil))]
      (do (when (= :shutdown (get-in (protocol/decode-command line) [:command :op]))
            (reset! stop-requested true))
          (.put lines line)
          (recur))
      (.put lines ::eof))))

(defn- serve-from
  "`serve`, from hook state `hs`."
  [emit! decide next-line flush! hs]
  (let [more #(serve-from emit! decide next-line flush! %)]
    (loop [hs hs]
      (if-let [line (next-line)]
        (let [next-hs (binding [*in* (java.io.BufferedReader. (java.io.StringReader. ""))]
                        (handle-line! emit! decide more hs line))]
          (flush!)
          (cond
            (= :shutdown next-hs) hs
            (reduced? next-hs) @next-hs
            :else (recur next-hs)))
        hs))))

(defn serve
  "Handle the parent's commands, a line from `(next-line)` at a time, until
  `{:op :shutdown}` or the end of input (nil), calling `(flush!)` (by
  default `flush`) after each. Returns the final hook state. Tests get an
  empty `*in*`, so a test that reads it cannot block on or swallow a
  command."
  ([emit! decide next-line] (serve emit! decide next-line flush))
  ([emit! decide next-line flush!] (serve-from emit! decide next-line flush! (hooks/init nil))))

(defn -main
  "Worker entry point. Protocol events go to the original stdout; everything
  else a test prints goes to stderr. The one argument, when given, is the
  nonce of the protocol prefix (`volley.protocol/prefix-for`)."
  [& [nonce]]
  (let [proto-out (utf8-writer System/out)
        stray (java.io.PrintStream. System/err true "UTF-8")
        ^java.io.Writer stray-writer (utf8-writer stray)
        emit! (make-emit proto-out (protocol/prefix-for nonce))
        decide filters/decide
        in (java.io.BufferedReader.
            (java.io.InputStreamReader. System/in "UTF-8"))]
    ;; stdin carries the parent's commands. A test that reads System/in
    ;; would block on it (and swallow a command), so tests get an empty
    ;; stream, as *in* is an empty reader for them.
    (System/setIn (java.io.ByteArrayInputStream. (byte-array 0)))
    ;; A test that calls System/exit takes its capture buffer with it.
    ;; Send what it printed as output on the protocol stream, which keeps
    ;; it ordered after the test's :var/start.
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (when-let [text (some-> @live-capture capture-text)]
                                   (emit! {:event :output :text text :stream :stdout
                                           :captured? true})))))
    (System/setOut stray)
    (alter-var-root #'*out* (constantly stray-writer))
    (alter-var-root #'*err* (constantly stray-writer))
    ;; the project's reporters print here (`volley.shell.hooks/report-fn`)
    (alter-var-root #'t/*test-out* (constantly stray-writer))
    (emit! {:event :worker/ready})
    (let [lines (java.util.concurrent.LinkedBlockingQueue.)
          _ (doto (Thread. ^Runnable #(read-commands! in lines)) (.setDaemon true) (.start))
          hs (serve emit! decide
                    #(let [line (.take lines)] (when-not (= ::eof line) line))
                    #(.flush stray-writer))
          errors (hooks/shutdown hs)]
      (report-hook-errors! emit! errors)
      (.flush stray-writer)
      (shutdown-agents)
      (System/exit (if (seq errors) 1 0)))))

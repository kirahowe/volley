(ns brisk.protocol
  "Encode and decode the messages between the parent and its workers.

  Parent to worker: one EDN map per line (`encode-command`).
  Worker to parent: one `#brisk/event `-prefixed EDN map per line
  (`encode-event`). Any other line is stray output.

  Pure: printing is bounded by structurally truncating values before they
  reach the printer, so no dynamic bindings are needed."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def event-prefix
  "Marks a worker stdout line as a protocol event."
  "#brisk/event ")

;; ---------------------------------------------------------------------------
;; Parent -> worker

(defn encode-command
  "Parent-to-worker message map -> one line, without the trailing newline."
  [command]
  (pr-str command))

(defn decode-command
  "One stdin line -> `{:kind :command :command map}`, or
  `{:kind :invalid :text line :error msg}`. Never throws."
  [line]
  (let [text (str line)]
    (try
      (let [v (edn/read-string text)]
        (if (and (map? v) (keyword? (:op v)))
          {:kind :command :command v}
          {:kind :invalid :text text :error "not a map with an :op keyword"}))
      (catch Throwable e
        {:kind :invalid :text text :error (or (ex-message e) (str (class e)))}))))

;; ---------------------------------------------------------------------------
;; Worker -> parent

(defn encode-event
  "Event map -> one protocol line (prefix included, no trailing newline).
  The result never contains a raw newline because `pr-str` escapes them."
  [event]
  (str event-prefix (pr-str event)))

(defn decode-line
  "One line of worker output -> a message.

  * `{:kind :event :event map}` for a well-formed protocol line.
  * `{:kind :output :text line}` for anything else.

  A line that carries the prefix but whose payload is not a map with an
  `:event` keyword is kept as output, with `:protocol-error msg` added, so
  nothing the worker printed is lost and the parent can flag the breakage.
  Total: never throws, accepts nil."
  [line]
  (let [text (str line)]
    (if-not (str/starts-with? text event-prefix)
      {:kind :output :text text}
      (try
        (let [v (edn/read-string (subs text (count event-prefix)))]
          (if (and (map? v) (keyword? (:event v)))
            {:kind :event :event v}
            {:kind :output :text text
             :protocol-error "payload is not a map with an :event keyword"}))
        (catch Throwable e
          {:kind :output :text text
           :protocol-error (or (ex-message e) (str (class e)))})))))

(defn ->event
  "Turn a decoded message into the flat event the parent's loop consumes.
  `ctx` is `{:worker id :at millis :stream :stdout|:stderr}`.

  Events keep their keys and gain `:worker` and `:at`. Stray output becomes
  `{:event :output :text str :stream kw ...}` (plus `:protocol-error`)."
  [message {:keys [worker at stream]}]
  (case (:kind message)
    :event (assoc (:event message) :worker worker :at at)
    (cond-> {:event :output :text (:text message) :stream stream
             :worker worker :at at}
      (:protocol-error message) (assoc :protocol-error (:protocol-error message)))))

;; ---------------------------------------------------------------------------
;; Values

(def default-print-opts
  "Bounds used when none are passed to `encode-value`."
  {:print-length 100 :print-level 8 :max-chars 10000})

(def ^:private more '...)

(declare bound*)

(defn- bound-all
  "Bound at most `:print-length` of `xs` with `f`, which takes an item and
  returns `[bounded exact?]`. Returns `{:items :over? :exact?}`."
  [f xs {:keys [print-length]}]
  (let [taken (take (inc print-length) xs)
        results (map f (take print-length taken))]
    {:items (mapv first results)
     :over? (> (count taken) print-length)
     :exact? (and (<= (count taken) print-length) (every? second results))}))

(defn- bound-seqable [v opts level]
  (let [{:keys [items over? exact?]} (bound-all #(bound* % opts (inc level)) v opts)
        items (cond-> items over? (conj more))]
    [(cond
       (vector? v) items
       (set? v) (into (if exact? (empty v) #{}) items)
       (seq? v) (apply list items)
       exact? (into (empty v) items)
       :else (apply list items))
     exact?]))

(defn- bound-map [m opts level]
  (let [bound-entry (fn [[k v]]
                      (let [[k' ke] (bound* k opts (inc level))
                            [v' ve] (bound* v opts (inc level))]
                        [[k' v'] (and ke ve)]))
        {:keys [items over? exact?]} (bound-all bound-entry m opts)
        out (into (if exact? (empty m) {}) items)]
    [(cond-> out over? (assoc more more)) exact?]))

(defn- bound-record [r opts level]
  (reduce (fn [[acc exact?] [k v]]
            (let [[v' e?] (bound* v opts (inc level))]
              [(assoc acc k v') (and exact? e?)]))
          [r true]
          r))

(defn- bound*
  "Copy `v` with at most `:print-length` items per collection and
  `:print-level` nesting. Returns `[bounded exact?]`; `exact?` is false when
  anything was cut."
  [v {:keys [print-level] :as opts} level]
  (cond
    (not (coll? v)) [v true]
    (>= level print-level) [more false]
    (record? v) (bound-record v opts level)
    (map? v) (bound-map v opts level)
    :else (bound-seqable v opts level)))

(defn bound-value
  "Bounded copy of `v`: collections keep at most `:print-length` items and
  `:print-level` levels, with a `...` symbol marking each cut. Lazy and
  infinite seqs are only realized that far. Returns `[bounded exact?]`."
  [v opts]
  (bound* v (merge default-print-opts opts) 0))

(defn- edn-round-trips?
  "True when printing `v` and reading it back yields an equal value."
  [v s]
  (try
    (= [v] (edn/read-string (str "[" s "]")))
    (catch Throwable _ false)))

(defn encode-value
  "Wire form of a test value: `{:str (pr-str value)}`, plus `:edn value`
  only when the value survives an EDN round trip untruncated.

  `opts` may carry `:print-length`, `:print-level` and `:max-chars`
  (defaults 100, 8 and 10000). Text longer than `:max-chars` is cut, and
  then has no `:edn`.
  Records, regexes, NaN, functions and anything else EDN cannot read back
  get `:str` only. Never throws: a value whose printing throws is reported as
  an unprintable placeholder."
  ([v] (encode-value v nil))
  ([v opts]
   (try
     (let [{:keys [max-chars]} (merge default-print-opts opts)
           [bounded exact?] (bound-value v opts)
           s (pr-str bounded)]
       (if (> (count s) max-chars)
         {:str (str (subs s 0 max-chars) "...")}
         (cond-> {:str s}
           (and exact? (edn-round-trips? bounded s)) (assoc :edn bounded))))
     (catch Throwable e
       {:str (str "#<unprintable " (or (ex-message e) (str (class e))) ">")}))))

;; ---------------------------------------------------------------------------
;; Errors

(def default-runner-prefixes
  "Class-name prefixes of stack frames that belong to the runner, not to the
  code under test."
  ["brisk." "clojure.test$" "clojure.test/" "clojure.main" "clojure.lang.Compiler"
   "clojure.core.server" "babashka." "sci."])

(defn- class-name [t]
  (cond
    (class? t) (.getName ^Class t)
    :else (str t)))

(defn- runner-frame? [prefixes [cls]]
  (let [c (class-name cls)]
    (boolean (some #(str/starts-with? c %) prefixes))))

(defn- frame->str [[cls method file line]]
  (let [loc (cond (and file (pos? (or line 0))) (str file ":" line)
                  file (str file)
                  :else "unknown")]
    (str (class-name cls) "." method " (" loc ")")))

(defn trim-trace
  "Cut a `Throwable->map`-style frame vector at the first runner frame (the
  test machinery that called the user's code, and everything below it) and
  render the frames above it as strings. At most `:max-frames` (default 50)
  are kept."
  [trace {:keys [runner-prefixes max-frames]
          :or {runner-prefixes default-runner-prefixes max-frames 50}}]
  (->> trace
       (take-while #(not (runner-frame? runner-prefixes %)))
       (take max-frames)
       (mapv frame->str)))

(defn encode-error
  "`Throwable->map`-style data -> `{:class :message :data :trace :cause}`.

  The `:via` chain becomes nested `:cause`s, outermost first. `Throwable->map`
  carries one stack trace (the root cause's), so it lands on the innermost
  error; outer errors get an empty trace. `:data` is the bounded `pr-str` of
  `ex-data`, or nil. Runner frames are removed from the trace."
  ([tm] (encode-error tm nil))
  ([tm opts]
   (let [via (or (seq (:via tm)) [{:type (:type tm) :message (:cause tm)}])
         trace (trim-trace (:trace tm) (or opts {}))
         build (fn build [[{:keys [type message data]} & more]]
                 (cond-> {:class (class-name type)
                          :message message
                          :data (when (some? data) (:str (encode-value data opts)))
                          :trace (if more [] trace)}
                   more (assoc :cause (build more))))]
     (build via))))

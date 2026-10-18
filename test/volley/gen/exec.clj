(ns volley.gen.exec
  "Generators and a deterministic scheduler simulation for the execution
  area: protocol messages and `volley.schedule`."
  (:require
   [volley.protocol :as protocol]
   [volley.schedule :as sched]
   [clojure.test.check.generators :as gen]))

;; ---------------------------------------------------------------------------
;; Protocol

(def gen-scalar
  "Values that print and read back as themselves in EDN."
  (gen/one-of [gen/small-integer
               gen/large-integer
               (gen/double* {:NaN? false :infinite? false})
               gen/boolean
               (gen/return nil)
               gen/string
               gen/string-ascii
               gen/char
               gen/keyword
               gen/keyword-ns
               ;; a symbol named nil, true or false reads back as that literal
               (gen/such-that #(not (contains? #{"nil" "true" "false"} (name %))) gen/symbol)
               gen/symbol-ns
               gen/ratio
               gen/uuid]))

(def gen-edn
  "Nested EDN data: vectors, lists, sets, maps."
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 4)
                  (gen/list inner)
                  (gen/set inner {:max-elements 4})
                  (gen/map inner inner {:max-elements 3})]))
   gen-scalar))

(def gen-event-kind
  "A worker event's `:event`."
  (gen/elements [:worker/ready :ns/start :ns/error :var/start :assertion
                 :var/end :var/skipped :ns/end]))

(def gen-event
  "An arbitrary worker event map: a known `:event` plus random EDN fields."
  (gen/let [kind gen-event-kind
            fields (gen/map gen/keyword gen-edn {:max-elements 6})]
    (assoc fields :event kind)))

(def gen-command
  "A parent-to-worker message with random fields."
  (gen/let [op (gen/elements [:run :shutdown])
            fields (gen/map gen/keyword gen-edn {:max-elements 4})]
    (assoc fields :op op)))

(def gen-line
  "Any string a worker might print: arbitrary text, text starting with the
  event prefix, truncated protocol lines, and real encoded events."
  (gen/one-of [gen/string
               gen/string-ascii
               (gen/fmap #(str protocol/event-prefix %) gen/string)
               (gen/fmap #(str protocol/event-prefix %) (gen/fmap pr-str gen-edn))
               (gen/fmap protocol/encode-event gen-event)
               (gen/fmap #(subs (protocol/encode-event %) 0 (quot (count (protocol/encode-event %)) 2))
                         gen-event)]))

(defrecord Point [x y])

(def nasty-leaves
  "Values EDN cannot round-trip, or that never finish printing. Kept out of
  generated data (printing a failing case would hang): generators produce
  keyword recipes and `build-nasty` swaps in the real values."
  {:nasty/record (->Point 1 2)
   :nasty/regex #"re+gex"
   :nasty/nan ##NaN
   :nasty/inf ##Inf
   :nasty/odd-symbol (symbol "a b")
   :nasty/odd-keyword (keyword "with space")
   :nasty/fn inc
   :nasty/atom (atom 1)
   :nasty/object (Object.)
   :nasty/range (range)
   :nasty/pairs (map vector (range) (range))
   :nasty/deep (iterate vector [])
   :nasty/repeat (repeat (repeat 3))
   :nasty/sorted-map (sorted-map 1 :a 2 :b)
   :nasty/sorted-set (sorted-set 3 1 2)
   :nasty/java-list (java.util.ArrayList. [1 2 3])
   :nasty/throwable (ex-info "boom" {:a 1})})

(def gen-nasty
  "A recipe: EDN data with `:nasty/*` leaves and `{:nasty/point x}` wrappers.
  Turn it into a value with `build-nasty`."
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 4)
                  (gen/list inner)
                  (gen/map gen/keyword inner {:max-elements 3})
                  (gen/fmap (fn [x] {:nasty/point x}) inner)]))
   (gen/one-of [gen-scalar (gen/elements (vec (keys nasty-leaves)))])))

(defn build-nasty
  "Realize a `gen-nasty` recipe."
  [recipe]
  (cond
    (and (map? recipe) (contains? recipe :nasty/point))
    (let [x (build-nasty (:nasty/point recipe))] (->Point x x))
    (map? recipe) (update-vals recipe build-nasty)
    (vector? recipe) (mapv build-nasty recipe)
    (seq? recipe) (apply list (map build-nasty recipe))
    (keyword? recipe) (get nasty-leaves recipe recipe)
    :else recipe))

(def gen-frame
  "A `Throwable->map` stack frame, user or runner code."
  (gen/let [cls (gen/elements ["my.app$f" "my.app.core" "java.lang.Integer" "volley.shell.worker$run"
                               "clojure.test$test_var" "babashka.impl.clojure.test$x" "clojure.core$map"])
            method (gen/elements ["invoke" "invokeStatic" "parseInt"])
            line (gen/choose -1 500)]
    [(symbol cls) (symbol method) "file.clj" line]))

(def gen-throwable-map
  "Data shaped like `Throwable->map`."
  (gen/let [via (gen/vector (gen/let [type (gen/elements ['java.lang.RuntimeException 'clojure.lang.ExceptionInfo])
                                      message (gen/one-of [gen/string (gen/return nil)])
                                      data (gen/one-of [(gen/map gen/keyword gen-edn {:max-elements 3}) (gen/return nil)])]
                                {:type type :message message :data data})
                            1 4)
            trace (gen/vector gen-frame 0 20)]
    {:via via :trace trace :cause (:message (peek via))}))

;; ---------------------------------------------------------------------------
;; Scheduler

(defn- ns-syms [prefix n]
  (mapv #(symbol (str prefix %)) (range n)))

(def gen-plan
  "A scheduler plan of parallel and serial namespaces."
  (gen/let [np (gen/choose 0 9)
            ns* (gen/choose 0 3)]
    {:parallel (ns-syms "p" np) :serial (ns-syms "s" ns*)}))

(def gen-sched-opts
  "Options for `volley.schedule/init`."
  (gen/let [workers (gen/choose 1 5)
            fail-fast? gen/boolean
            isolate? gen/boolean
            durations (gen/map (gen/fmap #(symbol (str "p" %)) (gen/choose 0 9))
                               (gen/choose 1 5000)
                               {:max-elements 6})]
    {:workers workers :fail-fast? fail-fast? :isolate? isolate? :durations durations}))

(def gen-script
  "Random choices that drive `simulate`."
  (gen/vector (gen/choose 0 1000000) 0 300))

(defn- enabled-actions
  "Everything that could happen next in the outside world. Order matters:
  with choice 0 the simulation always makes progress."
  [{:keys [alive running shutdown]} chaos]
  (vec
   (concat
    (for [[w st] (sort alive) :when (= :starting st)] [:ready w])
    (for [[w _] (sort running)
          action (concat (repeat 3 :complete) (repeat chaos :fail) (repeat chaos :crash))]
      [action w])
    (for [w (sort shutdown) :when (contains? alive w)] [:exit w]))))

(defn- worker-event [ev worker ns-sym]
  (case ev
    :ready [{:event :worker/ready :worker worker}]
    :complete [{:event :ns/end :worker worker :ns ns-sym}]
    :fail [{:event :assertion :type :fail :worker worker :ns ns-sym}
           {:event :ns/end :worker worker :ns ns-sym}]
    :crash [{:event :worker/exit :worker worker :code 1}]
    :exit [{:event :worker/exit :worker worker :code 0}]))

(defn- violation [model kind detail]
  (update model :violations conj (assoc detail :violation kind)))

(defn- check-assignment [{:keys [alive running failure-seen? serial-all fail-fast?]} [w ns-sym]]
  (cond-> []
    (not= :ready (get alive w)) (conj :assigned-to-unready-worker)
    (contains? running w) (conj :worker-holds-two)
    (and failure-seen? fail-fast?) (conj :assigned-after-fail-fast-failure)
    (and (contains? serial-all ns-sym) (seq running)) (conj :serial-overlaps-running)
    (some serial-all (vals running)) (conj :assigned-during-serial)))

(defn- apply-assignments [model assigns]
  (reduce (fn [m [w ns-sym :as a]]
            (-> (reduce #(violation %1 %2 {:assignment a}) m (check-assignment m a))
                (assoc-in [:running w] ns-sym)))
          model assigns))

(defn- act
  "Ask the scheduler what to do and record it in the outside-world model."
  [state model]
  (let [[state spawned] (sched/drain-spawns state)
        model (-> model
                  (update :alive into (map (fn [w] [w :starting]) spawned))
                  (update :spawned into spawned))
        [state assigns] (sched/next-assignments state)
        model (apply-assignments model assigns)
        [state shut] (sched/drain-shutdowns state)
        model (update model :shutdown into shut)
        live (count (remove (:shutdown model) (keys (:alive model))))]
    [state
     (cond-> model
       (> live (:max-workers model)) (violation :too-many-workers {:live live}))]))

(defn- deliver-event
  "The outside world produces `ev`; feed it to the scheduler."
  [state model action]
  (let [[kind w] action
        ns-sym (get-in model [:running w])
        events (worker-event kind w ns-sym)
        crash (when (= :crash kind) (sched/crash-events state (first events)))
        model (cond-> model
                (= :ready kind) (assoc-in [:alive w] :ready)
                (#{:complete :fail} kind) (update :running dissoc w)
                (#{:crash :exit} kind) (-> (update :alive dissoc w) (update :running dissoc w))
                (= :fail kind) (assoc :failure-seen? true)
                (= :crash kind) (assoc :failure-seen? true)
                (and (= :crash kind) (not= 2 (count crash))) (violation :crash-events {:crash crash}))]
    [(reduce sched/handle-event state events) model]))

(defn simulate
  "Run the scheduler against a random outside world.

  `script` is a vector of non-negative ints; each picks one of the currently
  possible happenings (worker ready, ns finished, ns failed, worker crashed,
  retired worker exited). `chaos` (0 to 3) sets how often failures and
  crashes are offered. When the script runs out, choice 0 is used, which
  always makes progress. Returns
  `{:state s :violations [...] :done? bool :spawned [...]}`."
  [plan opts script chaos]
  (let [state (sched/init plan opts)
        model {:alive {} :running {} :shutdown #{} :spawned [] :violations []
               :failure-seen? false :serial-all (set (:serial plan))
               :fail-fast? (:fail-fast? opts) :max-workers (:workers opts)}
        [state model] (act state model)]
    (loop [state state model model script script steps 0]
      (let [actions (enabled-actions model chaos)]
        (if (or (sched/done? state) (empty? actions) (> steps 2000))
          {:state state :violations (:violations model) :done? (sched/done? state)
           :spawned (:spawned model) :stuck? (and (empty? actions) (not (sched/done? state)))}
          (let [choice (or (first script) 0)
                action (nth actions (mod choice (count actions)))
                [state' model'] (deliver-event state model action)
                [state'' model''] (act state' model')]
            (recur state'' model'' (rest script) (inc steps))))))))

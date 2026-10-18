(ns brisk.gen.report
  "Generators for worker event streams, run states and the values that
  appear in assertions. Streams respect each worker's own event order;
  `interleave-streams` merges them in a way chosen by a vector of ints."
  (:require
   [brisk.run-state :as rs]
   [clojure.test.check.generators :as gen]))

;;; Values

(def scalar
  (gen/one-of [gen/large-integer gen/string gen/keyword gen/boolean
               (gen/return nil) gen/symbol gen/string-alphanumeric]))

(def edn-value
  "Arbitrary nested EDN data without NaN, so `(= x x)` always holds."
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 4)
                  (gen/list inner)
                  (gen/set inner {:max-elements 4})
                  (gen/map inner inner {:max-elements 4})]))
   scalar))

(def hostile-text
  "Strings with escape sequences, control characters, tabs and wide
  characters, which rendering must neutralize."
  (gen/one-of [gen/string
               (gen/elements ["\u001b[31mred\u001b[0m" "tab\there" "line1\nline2\r\nline3"
                              "日本語のテスト名" "emoji 🚀 test" "\u001b[2J\u001b[H" ""])]))

(def maybe-text (gen/one-of [(gen/return nil) hostile-text]))

(def huge-text
  (gen/fmap #(apply str (repeat 3000 %)) gen/char-alpha))

(defn- value-event [v]
  (gen/one-of [(gen/return {:str (pr-str v) :edn v})
               (gen/return {:str (pr-str v)})]))

(def value
  "An `{:str :edn}` value as sent by workers."
  (gen/bind edn-value value-event))

(def comparison
  "An [expected actual] pair in either of the shapes workers may send."
  (gen/bind
   (gen/tuple edn-value edn-value)
   (fn [[x y]]
     (gen/one-of
      [(gen/return [{:str (pr-str x) :edn x} {:str (pr-str y) :edn y}])
       (gen/return [{:str (str "(= " (pr-str x) " " (pr-str y) ")")
                     :edn (list '= x y)}
                    {:str (str "(not (= " (pr-str x) " " (pr-str y) "))")
                     :edn (list 'not (list '= x y))}])
       (gen/return [{:str (pr-str x)} {:str (pr-str y)}])]))))

(declare error)

(def ^:private error-base
  (gen/hash-map :class (gen/elements ["java.lang.Exception" "clojure.lang.ExceptionInfo" "java.lang.NullPointerException"])
                :message maybe-text
                :data maybe-text
                :trace (gen/vector (gen/fmap #(str "my.app$fn.invoke (app.clj:" % ")") gen/nat) 0 14)))

(def error
  "An error map `{:class :message :data :trace :cause}`."
  (gen/bind error-base
            (fn [e]
              (gen/one-of [(gen/return e)
                           (gen/fmap #(assoc e :cause %) error-base)]))))

;;; Events

(defn- ns-sym [i] (symbol (str "gen.ns" i "-test")))
(defn- var-sym [j] (symbol (str "t" j)))

(def check
  "The `:check` of a failed test.check property, as `brisk.protocol/encode-check`
  builds it."
  (gen/let [seed gen/large-integer
            num-tests gen/nat
            size gen/nat
            [smallest] comparison
            result (gen/one-of [(gen/return nil) (gen/fmap (fn [[v]] v) comparison)])]
    (cond-> {:seed seed :num-tests num-tests :failing-size size
             :smallest smallest :fail smallest}
      result (assoc :result result))))

(defn- assertion-event [ns var type]
  (gen/let [[expected actual] comparison
            message maybe-text
            contexts (gen/vector hostile-text 0 3)
            line gen/nat
            err error
            chk (gen/frequency [[4 (gen/return nil)] [1 check]])]
    (cond-> {:event :assertion :ns ns :var var :type type :message message
             :contexts contexts :file "test/gen_test.clj" :line (inc line)
             :expected expected :actual actual}
      (= :error type) (assoc :error err)
      (and chk (= :fail type)) (assoc :check chk))))

(defn- var-script [ns j]
  (let [var (var-sym j)]
    (gen/one-of
     [(gen/let [reason (gen/elements [:pending :skip-meta])]
        [{:event :var/skipped :ns ns :var var :reason reason}])
      (gen/let [pass gen/nat
                types (gen/vector (gen/elements [:fail :error]) 0 3)
                as (apply gen/tuple (map #(assertion-event ns var %) types))
                strays (gen/vector hostile-text 0 2)
                output maybe-text
                duration (gen/choose 0 5000)]
        (concat [{:event :var/start :ns ns :var var :file "test/gen_test.clj" :line 1}]
                (map (fn [t] {:event :output :text t :stream :stderr}) strays)
                as
                [{:event :var/end :ns ns :var var :pass pass
                  :fail (count (filter #(= :fail %) types))
                  :error (count (filter #(= :error %) types))
                  :duration-ms duration :output output}]))])))

(defn- ns-script [i]
  (let [ns (ns-sym i)]
    (gen/let [n (gen/choose 0 4)
              load-error? (gen/frequency [[1 (gen/return true)] [6 (gen/return false)]])
              vars (apply gen/tuple (map #(var-script ns %) (range n)))
              err error
              duration (gen/choose 1 9000)]
      (if load-error?
        [{:event :ns/start :ns ns :run-id i}
         {:event :ns/error :ns ns :run-id i :error err}
         {:event :ns/end :ns ns :run-id i :duration-ms duration}]
        (concat [{:event :ns/start :ns ns :run-id i}]
                (apply concat vars)
                [{:event :ns/end :ns ns :run-id i :duration-ms duration}])))))

(defn- stamp [worker start events]
  (vec (map-indexed (fn [k e] (assoc e :worker worker :at (+ start (* 10 k))))
                    events)))

(defn- open-ns
  "The namespace a worker is inside after `events`, or nil."
  [events]
  (reduce (fn [open {:keys [event ns]}]
            (case event
              :ns/start ns
              :ns/end nil
              open))
          nil events))

(defn crash-tail
  "What follows a worker dying with `code` after `events`: the scheduler's
  synthetic `:ns/error` and `:ns/end` when it held a namespace (see
  `brisk.schedule/crash-events`), then the pool's `:worker/exit`."
  [events code]
  (let [ns (open-ns events)]
    (cond-> []
      ns (into [{:event :ns/error :ns ns :synthetic? true
                 :error {:class "brisk.WorkerCrashed"
                         :message (str "worker process exited with code " code)
                         :data nil :trace [] :exit-code code}}
                {:event :ns/end :ns ns :synthetic? true :crashed? true}])
      true (conj {:event :worker/exit :code code}))))

(defn worker-stream
  "A generator of one worker's events for the namespaces numbered `ns-ids`:
  ready, then each namespace in order, then a clean exit, a crash partway
  through, or nothing."
  [worker ns-ids]
  (gen/let [scripts (apply gen/tuple (map ns-script ns-ids))
            start (gen/choose 1000 2000)
            ending (gen/elements [:exit :crash :none :none])
            cut gen/nat
            code (gen/choose 1 255)]
    (let [body (vec (cons {:event :worker/ready} (apply concat scripts)))
          events (case ending
                   :exit  (conj body {:event :worker/exit :code 0})
                   :none  body
                   :crash (let [prefix (vec (take (mod cut (inc (count body))) body))]
                            (into prefix (crash-tail prefix code))))]
      (stamp worker start events))))

(defn interleave-streams
  "Merges `streams` keeping each stream's order. `choices` is a seq of ints
  picking which non-empty stream supplies the next event."
  [streams choices]
  (loop [qs (vec (map seq streams)) cs (seq choices) out []]
    (let [live (vec (keep-indexed (fn [i q] (when q i)) qs))]
      (if (empty? live)
        out
        (let [i (if cs (nth live (mod (first cs) (count live))) (first live))]
          (recur (update qs i next) (next cs) (conj out (first (qs i)))))))))

(def choices (gen/vector gen/nat 0 400))

(def run
  "A generated run: `{:plan :streams :orders [c1 c2]}` with two interleaving
  choice vectors. Use `events` to merge."
  (gen/let [n-workers (gen/choose 1 4)
            n-ns (gen/choose 0 6)
            streams (apply gen/tuple
                           (map (fn [w] (worker-stream w (filter #(= w (mod % n-workers)) (range n-ns))))
                                (range n-workers)))
            extra-planned (gen/choose 0 3)
            c1 choices
            c2 choices]
    {:plan {:namespaces (map ns-sym (range (+ n-ns extra-planned)))}
     :streams streams
     :orders [c1 c2]}))

(defn events
  "The interleaving of a generated `run` selected by `which` (0 or 1)."
  [{:keys [streams orders]} which]
  (interleave-streams streams (nth orders which)))

(defn fold
  "Run state after applying `events` to a fresh state for `plan`."
  [plan events]
  (reduce rs/apply-event (rs/init plan) events))

(def state
  "A generated run state, partially or fully through a run."
  (gen/fmap (fn [{:keys [plan] :as r}] (fold plan (events r 0))) run))

(def partial-state
  "A state after a prefix of an interleaving, so some workers are mid-test."
  (gen/let [r run
            k gen/nat]
    (let [evs (events r 0)]
      {:plan (:plan r)
       :state (fold (:plan r) (take (mod k (inc (count evs))) evs))})))

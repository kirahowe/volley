(ns brisk.run-state
  "Folds worker events into the run's state, and derives summaries from it.

  The state is a plain map built only by `apply-event`. Every record is keyed
  by namespace and var, and timestamps are folded with min/max, so the final
  state depends only on each worker's own event order and not on how events
  from different workers interleave.

  Event shapes are defined in doc/design.md: the worker events plus what the
  pool adds (`{:event :output ...}` for stray output, `{:event :worker/exit
  :code n}`), the scheduler's synthetic crash events, and two the engine
  emits: `{:event :file/error :file path :error msg}` for a test file whose
  ns form cannot be read, and `{:event :run/not-run :namespaces [ns ...]
  :reason kw}` for planned namespaces that never ran.

  A worker crash is recorded once, from the synthetic `:ns/error` whose
  error class is `brisk.WorkerCrashed`. `:worker/exit` only marks the worker
  as gone."
  (:require
   [clojure.string :as str]))

;;; State

(defn init
  "Returns an empty run state. `plan-summary` is
  `{:namespaces [ns-sym ...] :started-at millis}`; both keys are optional.
  Planned namespaces that have not started count as pending."
  [plan-summary]
  {:planned    (set (:namespaces plan-summary))
   :started-at (:started-at plan-summary)
   :now        nil
   :workers    {}
   :namespaces {}
   :file-errors {}
   :not-run    {}})

(defn- min* [a b] (cond (nil? a) b (nil? b) a :else (min a b)))
(defn- max* [a b] (cond (nil? a) b (nil? b) a :else (max a b)))

(defn- touch-clock [state at]
  (-> state
      (update :started-at min* at)
      (update :now max* at)))

(defn- var-path [{:keys [ns var]}] [:namespaces ns :vars var])

(defn- var-record [{:keys [ns var]}]
  (fn [v] (merge {:ns ns :var var :assertions [] :phase :running} v)))

(defn- ns-record [ns]
  (fn [r] (merge {:ns ns :vars {} :status :running} r)))

(defn- set-worker [state worker m]
  (update-in state [:workers worker] merge {:id worker} m))

(def ^:private idle {:ns nil :var nil :ns-at nil :var-at nil})

(defn- on-ready [state {:keys [worker]}]
  (set-worker state worker {}))

(defn- on-ns-start [state {:keys [worker ns at]}]
  (-> state
      (update-in [:namespaces ns]
                 #(merge ((ns-record ns) %) {:worker worker :start-at at}))
      (set-worker worker {:ns ns :var nil :ns-at at :var-at nil})))

(def crash-class
  "Error class of the synthetic `:ns/error` the scheduler emits when a
  worker dies holding a namespace."
  "brisk.WorkerCrashed")

(defn- crash-error? [error] (= crash-class (:class error)))

(defn- crash-running-vars
  "Vars of a crashed namespace that were still running died with it."
  [vars]
  (update-vals vars #(cond-> % (= :running (:phase % :running)) (assoc :phase :crashed))))

(defn- on-ns-error [state {:keys [worker ns at error phase]}]
  (let [crash? (crash-error? error)]
    (-> state
        (update-in [:namespaces ns]
                   #(cond-> (merge ((ns-record ns) %)
                                   {:status (if crash? :crashed :error) :error error
                                    :phase phase :worker worker :end-at at})
                      crash? (-> (assoc :exit-code (:exit-code error))
                                 (update :vars crash-running-vars))))
        (set-worker worker idle))))

(defn- on-ns-end [state {:keys [worker ns at duration-ms]}]
  (-> state
      (update-in [:namespaces ns]
                 #(-> ((ns-record ns) %)
                      (update :status (fn [s] (if (#{:error :crashed} s) s :done)))
                      (assoc :end-at at :duration-ms duration-ms)))
      (set-worker worker idle)))

(defn- on-var-start [state {:keys [worker ns var file line at] :as e}]
  (-> state
      (update-in (var-path e) #(merge ((var-record e) %)
                                      {:file file :line line :start-at at}))
      (set-worker worker {:ns ns :var var :var-at at})))

(def ^:private assertion-keys
  [:type :message :contexts :expected :actual :file :line :error :at])

(defn- on-assertion [state e]
  (update-in state (var-path e)
             #(update ((var-record e) %) :assertions conj (select-keys e assertion-keys))))

(defn- on-var-end [state {:keys [worker at pass duration-ms output] :as e}]
  (-> state
      (update-in (var-path e)
                 #(assoc ((var-record e) %)
                         :phase :ended :pass (or pass 0) :duration-ms duration-ms
                         :output output :end-at at))
      (set-worker worker {:var nil :var-at nil})))

(defn- on-var-skipped [state {:keys [reason] :as e}]
  (update-in state (var-path e)
             #(assoc ((var-record e) %) :phase :skipped :reason reason)))

(defn- on-output [state {:keys [worker text]}]
  (let [{:keys [ns var]} (get-in state [:workers worker])
        path (cond (and ns var) [:namespaces ns :vars var :stray]
                   ns           [:namespaces ns :stray]
                   :else        [:workers worker :stray])]
    (update-in state path (fnil conj []) text)))

(defn- on-worker-exit
  "Informational: the worker is gone. Crashes are reported through the
  synthetic `:ns/error`, never from here."
  [state {:keys [worker code]}]
  (set-worker state worker (merge idle {:exited? true :code code})))

(defn- on-file-error [state {:keys [file error]}]
  (assoc-in state [:file-errors file] error))

(defn- on-not-run [state {:keys [namespaces reason]}]
  (update state :not-run into (map (fn [ns] [ns reason])) namespaces))

(defn apply-event
  "Folds one event into `state`. Events from one worker must arrive in that
  worker's order; events from different workers may arrive in any order.
  Unknown events leave the state unchanged apart from the clock."
  [state event]
  (let [state (touch-clock state (:at event))]
    (case (:event event)
      :worker/ready  (on-ready state event)
      :ns/start      (on-ns-start state event)
      :ns/error      (on-ns-error state event)
      :ns/end        (on-ns-end state event)
      :var/start     (on-var-start state event)
      :assertion     (on-assertion state event)
      :var/end       (on-var-end state event)
      :var/skipped   (on-var-skipped state event)
      :output        (on-output state event)
      :worker/exit   (on-worker-exit state event)
      :file/error    (on-file-error state event)
      :run/not-run   (on-not-run state event)
      state)))

;;; Derived values

(defn var-status
  "One of :running, :passed, :failed, :errored, :skipped, :crashed."
  [{:keys [phase assertions]}]
  (let [types (set (map :type assertions))]
    (case phase
      :skipped :skipped
      :crashed :crashed
      :ended   (cond (types :error) :errored
                     (types :fail)  :failed
                     :else          :passed)
      :running)))

(defn var-counts
  "Assertion counts for a var record: `{:pass n :fail n :error n}`. Failures
  and errors are counted from assertion events; passes come from :var/end."
  [{:keys [pass assertions]}]
  (let [n (fn [t] (count (filter #(= t (:type %)) assertions)))]
    {:pass (or pass 0) :fail (n :fail) :error (n :error)}))

(defn var-output
  "Everything a var printed: stray lines first, then captured output."
  [{:keys [stray output]}]
  (->> (concat stray [output])
       (remove #(or (nil? %) (str/blank? %)))
       (str/join "\n")))

(defn ns-result
  "One of :running, :passed, :failed, :error, :crashed."
  [{:keys [status vars]}]
  (case status
    :error   :error
    :crashed :crashed
    :done    (if (some (comp #{:failed :errored :crashed} var-status) (vals vars))
               :failed
               :passed)
    :running))

(defn- all-vars [state]
  (mapcat (comp vals :vars) (vals (:namespaces state))))

(defn summary
  "Counts for the run so far. `now` defaults to the latest event time."
  ([state] (summary state (:now state)))
  ([state now]
   (let [nses     (vals (:namespaces state))
         ns-freq  (frequencies (map ns-result nses))
         vars     (all-vars state)
         var-freq (frequencies (map var-status vars))
         counts   (map var-counts vars)
         sum      (fn [k] (reduce + (map k counts)))
         seen     (set (keys (:namespaces state)))
         pending  (count (remove seen (:planned state)))
         v        (fn [k] (get var-freq k 0))
         n        (fn [k] (get ns-freq k 0))]
     {:namespaces {:total   (+ (count nses) pending (count (:file-errors state)))
                   :passed  (n :passed)
                   :failed  (n :failed)
                   :errored (+ (n :error) (n :crashed) (count (:file-errors state)))
                   :running (n :running)
                   :pending pending}
      :vars       {:total   (reduce + (vals var-freq))
                   :passed  (v :passed)
                   :failed  (v :failed)
                   :errored (+ (v :errored) (v :crashed))
                   :skipped (v :skipped)
                   :running (v :running)}
      :assertions {:total   (+ (sum :pass) (sum :fail) (sum :error))
                   :passed  (sum :pass)
                   :failed  (sum :fail)
                   :errored (sum :error)}
      :wall-ms    (if (and now (:started-at state))
                    (max 0 (- now (:started-at state)))
                    0)})))

(defn- sort-key [{:keys [ns var line idx]}]
  [(str ns) (str var) (if (number? line) line 0) (or idx 0)])

(defn- assertion-failures [{:keys [ns vars]}]
  (for [v (vals vars)
        [idx a] (map-indexed vector (:assertions v))]
    (assoc a :kind (:type a) :ns ns :var (:var v) :idx idx
           :file (or (:file a) (:file v))
           :output (var-output v))))

(defn- ns-failures [{:keys [ns status error exit-code stray vars worker phase]}]
  (case status
    :error   [{:kind :ns-error :ns ns :error error :worker worker :phase phase
               :output (str/join "\n" stray)}]
    :crashed (let [v (first (filter #(= :crashed (var-status %)) (vals vars)))]
               [{:kind :crash :ns ns :var (:var v) :exit-code exit-code :worker worker
                 :line (:line v) :file (:file v)
                 :output (str/join "\n" (concat stray (when v [(var-output v)])))}])
    []))

(defn- file-failures [state]
  (for [[file error] (:file-errors state)]
    {:kind :file-error :file file :message error}))

(def counted-not-run?
  "Reasons a planned namespace did not run that make the run fail. Fail-fast
  leftovers do not count: Kaocha stops the same way and counts only what
  ran."
  (complement #{:fail-fast}))

(defn- not-run-failures [state]
  (for [[ns reason] (:not-run state)
        :when (counted-not-run? reason)]
    {:kind :not-run :ns ns :reason reason}))

(defn failures
  "Every failed assertion, errored assertion, namespace load error, worker
  crash, unreadable test file (`:file-error`) and namespace that could not
  be run (`:not-run`, except after fail-fast), ordered by namespace, var,
  line. Each carries :kind, :ns, and for assertions :var, :line, :message,
  :contexts, :expected, :actual, :error and the var's captured :output."
  [state]
  (->> (concat (mapcat assertion-failures (vals (:namespaces state)))
               (mapcat ns-failures (vals (:namespaces state)))
               (file-failures state)
               (not-run-failures state))
       (sort-by (juxt sort-key #(str (:kind %))))
       vec))

(defn slowest
  "The `n` slowest vars as `{:ns :var :duration-ms}`, slowest first."
  [state n]
  (->> (all-vars state)
       (filter #(number? (:duration-ms %)))
       (sort-by (juxt #(- (:duration-ms %)) #(str (:ns %)) #(str (:var %))))
       (take n)
       (map #(select-keys % [:ns :var :duration-ms]))))

(defn running
  "What each busy worker is doing: `{:worker :ns :var :ns-at :var-at :vars-done}`
  ordered by worker id. :var and :var-at are nil between tests."
  [state]
  (->> (vals (:workers state))
       (filter :ns)
       (sort-by :id)
       (map (fn [{:keys [id ns var ns-at var-at]}]
              {:worker id :ns ns :var var :ns-at ns-at :var-at var-at
               :vars-done (count (remove #(= :running (var-status %))
                                         (vals (get-in state [:namespaces ns :vars]))))}))))

(defn not-run
  "Planned namespaces that never ran, `{ns reason}`."
  [state]
  (:not-run state))

(defn exit-code
  "0 when nothing failed, otherwise the number of failures capped at 255,
  like Kaocha (`kaocha.runner/run`: `(min (+ error fail) 255)`). Load
  errors, unreadable files, crashed workers and namespaces that could not be
  run each count as one."
  [state]
  (min 255 (count (failures state))))

(defn result
  "Plain data about the finished run, for `--print-result` and for
  comparing against Kaocha: per-var status and counts keyed by fully
  qualified symbol, per-namespace result, unreadable files, namespaces that
  never ran, and the exit code."
  [state]
  {:vars (into (sorted-map)
               (map (fn [v]
                      [(symbol (str (:ns v)) (str (:var v)))
                       (cond-> (assoc (var-counts v) :status (var-status v))
                         (:reason v) (assoc :reason (:reason v)))]))
               (all-vars state))
   :namespaces (into (sorted-map)
                     (map (fn [[ns rec]] [ns (ns-result rec)]))
                     (:namespaces state))
   :file-errors (vec (sort (keys (:file-errors state))))
   :not-run (into (sorted-map) (:not-run state))
   :exit-code (exit-code state)})

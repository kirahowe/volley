(ns brisk.run-state
  "Folds worker events into the run's state, and derives summaries from it.

  The state is a plain map built only by `apply-event`. Every record is keyed
  by namespace and var, and timestamps are folded with min/max, so the final
  state depends only on each worker's own event order and not on how events
  from different workers interleave.

  Event shapes are defined in doc/design.md. Besides those, this namespace
  accepts `{:kind :output :worker id :text str}` for stray worker output and
  `{:event :worker/exit :worker id :exit-code n}` when a worker process ends."
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
   :namespaces {}})

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

(defn- on-ns-error [state {:keys [worker ns at error]}]
  (-> state
      (update-in [:namespaces ns]
                 #(merge ((ns-record ns) %)
                         {:status :error :error error :worker worker :end-at at}))
      (set-worker worker idle)))

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

(defn- crash-var [v]
  (cond-> v (= :running (:phase v :running)) (assoc :phase :crashed)))

(defn- crash-ns [state {:keys [worker at exit-code]} {:keys [ns var]}]
  (cond-> (update-in state [:namespaces ns]
                     #(assoc ((ns-record ns) %)
                             :status :crashed :exit-code exit-code
                             :worker worker :end-at at))
    var (update-in [:namespaces ns :vars var] #(crash-var (or % {:ns ns :var var})))))

(defn- on-worker-exit [state {:keys [worker exit-code] :as e}]
  (let [current (get-in state [:workers worker])
        in-ns?  (some? (:ns current))]
    (-> (if in-ns? (crash-ns state e current) state)
        (set-worker worker (merge idle {:exited? true :exit-code exit-code
                                        :crashed-ns (when in-ns? (:ns current))})))))

(defn- event-key [{:keys [event kind]}]
  (or event (when (= :output kind) :output)))

(defn apply-event
  "Folds one event into `state`. Events from one worker must arrive in that
  worker's order; events from different workers may arrive in any order.
  Unknown events leave the state unchanged apart from the clock."
  [state event]
  (let [state (touch-clock state (:at event))]
    (case (event-key event)
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
     {:namespaces {:total   (+ (count nses) pending)
                   :passed  (n :passed)
                   :failed  (n :failed)
                   :errored (+ (n :error) (n :crashed))
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

(defn- ns-failures [{:keys [ns status error exit-code stray vars worker]}]
  (case status
    :error   [{:kind :ns-error :ns ns :error error :worker worker
               :output (str/join "\n" stray)}]
    :crashed (let [v (first (filter #(= :crashed (var-status %)) (vals vars)))]
               [{:kind :crash :ns ns :var (:var v) :exit-code exit-code :worker worker
                 :line (:line v) :file (:file v)
                 :output (str/join "\n" (concat stray (when v [(var-output v)])))}])
    []))

(defn- idle-crashes [state]
  (for [[id w] (:workers state)
        :when (and (:exited? w) (not (:crashed-ns w))
                   (not (zero? (or (:exit-code w) 0))))]
    {:kind :crash :worker id :exit-code (:exit-code w) :idx id
     :output (str/join "\n" (:stray w))}))

(defn failures
  "Every failed assertion, errored assertion, namespace load error and worker
  crash, ordered by namespace, var, line. Each carries :kind, :ns, and for
  assertions :var, :line, :message, :contexts, :expected, :actual, :error and
  the var's captured :output."
  [state]
  (->> (concat (mapcat assertion-failures (vals (:namespaces state)))
               (mapcat ns-failures (vals (:namespaces state)))
               (idle-crashes state))
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

(defn exit-code
  "0 when nothing failed, otherwise the number of failures capped at 255,
  like Kaocha (`kaocha.runner/-main*`: `(min (+ error fail) 255)`). Load
  errors and crashed workers each count as one."
  [state]
  (min 255 (count (failures state))))

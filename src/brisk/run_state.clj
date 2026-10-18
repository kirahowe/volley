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
  ns form cannot be read, `{:event :run/not-run :namespaces [ns ...]
  :reason kw}` for planned namespaces that never ran, and
  `{:event :run/interrupted :namespaces [ns ...] :reason :fail-fast}` for
  namespaces whose worker was killed part way. Workers also send
  `{:event :worker/error :phase :shutdown :error err}` when a shutdown hook
  throws; it counts as one failure.

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
   :not-run    {}
   :interrupted {}
   :worker-errors {}
   :protocol-errors {}})

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

(defn- on-ns-end [state {:keys [worker ns at duration-ms pass]}]
  (-> state
      (update-in [:namespaces ns]
                 #(cond-> (-> ((ns-record ns) %)
                              (update :status (fn [s] (if (#{:error :crashed} s) s :done)))
                              (assoc :end-at at :duration-ms duration-ms))
                    pass (assoc :pass pass)))
      (set-worker worker idle)))

(defn- on-var-start [state {:keys [worker ns var file line at] :as e}]
  (-> state
      (update-in (var-path e) #(merge ((var-record e) %)
                                      {:file file :line line :start-at at}))
      (set-worker worker {:ns ns :var var :var-at at})))

(def ^:private assertion-keys
  [:type :message :contexts :expected :actual :file :line :error :check :at])

(defn- on-assertion
  "A failed assertion counts for its var, or, made outside any var (in a
  `:once` fixture), for the namespace."
  [state {:keys [ns var] :as e}]
  (if (some? var)
    (update-in state (var-path e)
               #(update ((var-record e) %) :assertions conj (select-keys e assertion-keys)))
    (update-in state [:namespaces ns]
               #(update ((ns-record ns) %) :assertions (fnil conj []) (select-keys e assertion-keys)))))

(defn- on-var-end [state {:keys [worker at pass fail error duration-ms output] :as e}]
  (-> state
      (update-in (var-path e)
                 #(assoc ((var-record e) %)
                         :phase :ended :pass (or pass 0) :duration-ms duration-ms
                         :reported {:fail (or fail 0) :error (or error 0)}
                         :output output :end-at at))
      (set-worker worker {:var nil :var-at nil})))

(defn- on-var-skipped [state {:keys [reason] :as e}]
  (update-in state (var-path e)
             #(assoc ((var-record e) %) :phase :skipped :reason reason)))

(defn- on-protocol-error
  "A protocol line the parent could not read. Whatever it said is lost, so
  it is a failure of its own, and of the namespace the worker was running."
  [state {:keys [worker text protocol-error at]}]
  (let [{:keys [ns var]} (get-in state [:workers worker])]
    (cond-> (update-in state [:protocol-errors worker] (fnil conj [])
                       {:worker worker :ns ns :var var :text text :message protocol-error :at at})
      ns (assoc-in [:namespaces ns :protocol-error?] true))))

(defn- on-output [state {:keys [worker text protocol-error] :as e}]
  (if protocol-error
    (on-protocol-error state e)
    (let [{:keys [ns var]} (get-in state [:workers worker])
          path (cond (and ns var) [:namespaces ns :vars var :stray]
                     ns           [:namespaces ns :stray]
                     :else        [:workers worker :stray])]
      (update-in state path (fnil conj []) text))))

(defn- on-worker-exit
  "Informational: the worker is gone. Crashes are reported through the
  synthetic `:ns/error`, never from here."
  [state {:keys [worker code]}]
  (set-worker state worker (merge idle {:exited? true :code code})))

(defn- on-worker-error [state {:keys [worker error phase]}]
  (update-in state [:worker-errors worker] (fnil conj []) {:worker worker :error error :phase phase}))

(defn- on-file-error [state {:keys [file error]}]
  (assoc-in state [:file-errors file] error))

(defn- on-not-run [state {:keys [namespaces reason]}]
  (update state :not-run into (map (fn [ns] [ns reason])) namespaces))

(defn- interrupt-ns
  "A namespace whose worker was stopped mid-run: the vars that finished
  keep their results, the one in progress is dropped."
  [rec ns at]
  (-> ((ns-record ns) rec)
      (assoc :status :interrupted :end-at at)
      (update :vars #(into {} (remove (fn [[_ v]] (= :running (:phase v :running)))) %))))

(defn- on-interrupted [state {:keys [namespaces reason at]}]
  (let [held (set namespaces)]
    (-> (reduce (fn [st ns] (update-in st [:namespaces ns] interrupt-ns ns at)) state namespaces)
        (update :interrupted into (map (fn [ns] [ns reason])) namespaces)
        (update :workers update-vals #(cond-> % (held (:ns %)) (merge idle))))))

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
      :worker/error  (on-worker-error state event)
      :run/not-run   (on-not-run state event)
      :run/interrupted (on-interrupted state event)
      state)))

;;; Derived values

(defn- lost-assertions
  "Failures and errors the worker counted in `:var/end` beyond the assertion
  events that arrived, `{:fail n :error n}`. Normally zero; positive when an
  assertion's protocol line was lost."
  [{:keys [reported assertions]}]
  (let [n (fn [t] (count (filter #(= t (:type %)) assertions)))]
    {:fail (max 0 (- (:fail reported 0) (n :fail)))
     :error (max 0 (- (:error reported 0) (n :error)))}))

(defn var-counts
  "Assertion counts for a var record: `{:pass n :fail n :error n}`. Failures
  and errors are counted from assertion events, or from the counts in
  :var/end when those are larger (assertion details were lost); passes come
  from :var/end."
  [{:keys [pass assertions] :as v}]
  (let [n (fn [t] (count (filter #(= t (:type %)) assertions)))
        lost (lost-assertions v)]
    {:pass (or pass 0)
     :fail (+ (n :fail) (:fail lost))
     :error (+ (n :error) (:error lost))}))

(defn var-status
  "One of :running, :passed, :failed, :errored, :skipped, :crashed."
  [{:keys [phase] :as v}]
  (case phase
    :skipped :skipped
    :crashed :crashed
    :ended   (let [{:keys [fail error]} (var-counts v)]
               (cond (pos? error) :errored
                     (pos? fail)  :failed
                     :else        :passed))
    :running))

(defn var-output
  "Everything a var printed: stray lines first, then captured output."
  [{:keys [stray output]}]
  (->> (concat stray [output])
       (remove #(or (nil? %) (str/blank? %)))
       (str/join "\n")))

(defn ns-counts
  "Assertions a namespace made outside its vars (in `:once` fixtures):
  `{:pass n :fail n :error n}`."
  [{:keys [pass assertions]}]
  (let [n (fn [t] (count (filter #(= t (:type %)) assertions)))]
    {:pass (or pass 0) :fail (n :fail) :error (n :error)}))

(defn ns-result
  "One of :running, :passed, :failed, :error, :crashed, :interrupted."
  [{:keys [status vars protocol-error? assertions]}]
  (case status
    :error   :error
    :crashed :crashed
    :interrupted :interrupted
    :done    (if (or protocol-error? (seq assertions)
                     (some (comp #{:failed :errored :crashed} var-status) (vals vars)))
               :failed
               :passed)
    :running))

(def ^:private filter-reasons
  "Skip reasons that mean the filters did not select a var."
  #{:skip :skip-meta :not-focused})

(defn unselected?
  "True for a namespace that finished without running or reporting
  anything because the filters selected none of its vars (or it has
  none). The plan cannot always tell beforehand, since tests defined by
  macros are invisible to it. Kaocha does not report such a namespace
  (`kaocha.testable/run-testable` skips a group whose tests are all
  skipped), so it is neither listed nor counted."
  [{:keys [status vars protocol-error? assertions pass]}]
  (and (= :done status) (not protocol-error?) (empty? assertions) (not (pos? (or pass 0)))
       (every? #(and (= :skipped (:phase %)) (contains? filter-reasons (:reason %))) (vals vars))))

(defn- all-vars [state]
  (mapcat (comp vals :vars) (vals (:namespaces state))))

(defn- counted-vars [state]
  (mapcat (comp vals :vars) (remove unselected? (vals (:namespaces state)))))

(defn summary
  "Counts for the run so far. `now` defaults to the latest event time."
  ([state] (summary state (:now state)))
  ([state now]
   (let [nses     (remove unselected? (vals (:namespaces state)))
         ns-freq  (frequencies (map ns-result nses))
         vars     (counted-vars state)
         var-freq (frequencies (map var-status vars))
         counts   (concat (map var-counts vars) (map ns-counts nses))
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
                   :interrupted (n :interrupted)
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

(defn- lost-failures
  "One failure per kind for the assertions a var's `:var/end` counted but
  whose events never arrived; `:lost` is how many it stands for."
  [ns v]
  (for [[kind n] (sort-by key (lost-assertions v))
        :when (pos? n)]
    {:kind kind :type kind :ns ns :var (:var v) :lost n :idx Long/MAX_VALUE
     :file (:file v) :line (:line v) :output (var-output v)
     :message (str n (if (= :fail kind) " failed assertion" " error")
                   (when (> n 1) "s")
                   " counted by the worker, but the details never arrived"
                   " (the protocol line was lost or could not be read)")}))

(defn- assertion-failures [{:keys [ns vars stray] :as rec}]
  (concat
   (for [[idx a] (map-indexed vector (:assertions rec))]
     (assoc a :kind (:type a) :ns ns :var nil :idx idx :output (str/join "\n" stray)))
   (for [v (vals vars)
         [idx a] (map-indexed vector (:assertions v))]
     (assoc a :kind (:type a) :ns ns :var (:var v) :idx idx
            :file (or (:file a) (:file v))
            :output (var-output v)))
   (mapcat #(lost-failures ns %) (vals vars))))

(defn- ns-failures [{:keys [ns status error exit-code stray vars worker phase]}]
  (case status
    :error   [(cond-> {:kind :ns-error :ns ns :error error :worker worker :phase phase
                       :output (str/join "\n" stray)}
                (some #(= :fixture-failed (:reason %)) (vals vars))
                (assoc :not-run-vars (vec (sort (keep #(when (= :fixture-failed (:reason %)) (:var %))
                                                      (vals vars))))))]
    :crashed (let [v (first (filter #(= :crashed (var-status %)) (vals vars)))]
               [{:kind :crash :ns ns :var (:var v) :exit-code exit-code :worker worker
                 :message (:reason error)
                 :line (:line v) :file (:file v)
                 :output (str/join "\n" (concat stray (when v [(var-output v)])))}])
    []))

(defn- worker-failures [state]
  (for [[_ errors] (sort-by key (:worker-errors state))
        {:keys [worker error phase]} errors]
    {:kind :worker-error :worker worker :error error :phase phase}))

(defn- protocol-failures [state]
  (for [[_ errors] (sort-by key (:protocol-errors state))
        {:keys [worker ns var text message]} errors]
    {:kind :protocol-error :worker worker :ns ns :var var :text text :reason message}))

(defn- file-failures [state]
  (for [[file error] (:file-errors state)]
    {:kind :file-error :file file :message error}))

(def counted-not-run?
  "Reasons a planned namespace did not run that make the run fail. Fail-fast
  leftovers do not count: Kaocha stops the same way and counts only what
  ran."
  (complement #{:fail-fast}))

(defn- unfinished
  "Planned namespaces that never reached a terminal status and are not
  accounted for as not run or interrupted, `{ns :unfinished}`. During a
  run these are the ones waiting or running. At the end there should be
  none; one that is left (a worker that vanished without a crash being
  reported, say) counts toward the exit code, so it cannot pass silently.
  It is not in `failures`, which describe what went wrong in what ran."
  [state]
  (into (sorted-map)
        (comp (remove #(contains? (:not-run state) %))
              (remove #(contains? (:interrupted state) %))
              (filter #(= :running (ns-result (get-in state [:namespaces %] {:status :running}))))
              (map (fn [ns] [ns :unfinished])))
        (:planned state)))

(defn- not-run-failures [state]
  (for [[ns reason] (:not-run state)
        :when (counted-not-run? reason)]
    {:kind :not-run :ns ns :reason reason}))

(def ^:private failure-order (juxt sort-key #(str (:kind %))))

(defn ns-failures-of
  "`failures` of namespace `ns` alone, in the same order: what
  `(filter #(= ns (:ns %)) (failures state))` gives, without looking at
  every other namespace."
  [state ns]
  (let [rec (get-in state [:namespaces ns])
        own? #(= ns (:ns %))]
    (->> (concat (when rec (assertion-failures rec))
                 (when rec (ns-failures rec))
                 (filter own? (protocol-failures state))
                 (when (contains? (:not-run state) ns) (filter own? (not-run-failures state))))
         (sort-by failure-order)
         vec)))

(defn failures
  "Every failed assertion, errored assertion, namespace load error, worker
  crash, hook that failed as a worker shut down (`:worker-error`),
  unreadable test file (`:file-error`), protocol line the parent could not
  read (`:protocol-error`) and namespace that could not
  be run (`:not-run`, except after fail-fast), ordered by namespace, var,
  line. Each carries :kind, :ns, and for assertions :var, :line, :message,
  :contexts, :expected, :actual, :error and the var's captured :output."
  [state]
  (->> (concat (mapcat assertion-failures (vals (:namespaces state)))
               (mapcat ns-failures (vals (:namespaces state)))
               (file-failures state)
               (worker-failures state)
               (protocol-failures state)
               (not-run-failures state))
       (sort-by failure-order)
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
  "Planned namespaces that never ran or never finished, `{ns reason}`.
  Reason `:unfinished` marks a namespace that is neither finished nor
  accounted for otherwise; during a run that is every namespace still
  waiting or running."
  [state]
  (merge (:not-run state) (unfinished state)))

(defn interrupted
  "Namespaces stopped part way, `{ns reason}`: with fail-fast, the ones
  other workers were running when the first failure arrived. They are
  listed, not counted as failures."
  [state]
  (:interrupted state))

(defn exit-code
  "0 when nothing failed, otherwise the number of failures capped at 255,
  like Kaocha (`kaocha.runner/run`: `(min (+ error fail) 255)`). Load
  errors, unreadable files, crashed workers, protocol lines that could not
  be read, namespaces that could not be run and planned namespaces that
  never finished each count as one;
  assertions whose details were lost count as many as were lost."
  [state]
  (min 255 (+ (reduce + (map #(:lost % 1) (failures state)))
              (count (unfinished state)))))

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
   :not-run (into (sorted-map) (not-run state))
   :interrupted (into (sorted-map) (:interrupted state))
   :exit-code (exit-code state)})

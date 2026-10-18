(ns brisk.engine
  "The parent's event loop as a pure state machine.

  `(step state input)` returns `{:state state' :effects [...]}`. Inputs are
  the flat events the pool puts on the queue (worker events, `:output`,
  `:worker/exit`) and timer ticks `{:event :tick :at ms}`. Effects are data
  for `brisk.main` to carry out:

      {:effect :spawn :worker id}
      {:effect :send :worker id :msg m}
      {:effect :stop :worker id}               ; ask a worker to shut down
      {:effect :kill :worker id}               ; it did not exit in time
      {:effect :print :lines [str] :stream :out|:err}
      {:effect :redraw :frame {:emit [str] :frame [str]}}
      {:effect :save-timings :timings {ns millis}}
      {:effect :post-summary :hooks [entry] :result kaocha-result}
      {:effect :exit :code n}

  `step` composes `brisk.schedule` (who runs what), `brisk.run-state` (what
  happened) and `brisk.render` (what to show). It never touches processes or
  the terminal.

  `preflight` makes the decisions that come before the loop: help, version,
  config and CLI errors, printing the config or plan, and \"no tests found\",
  each with Kaocha's exit code."
  (:require
   [brisk.hooks :as hooks]
   [brisk.render :as render]
   [brisk.run-state :as rs]
   [brisk.schedule :as sched]
   [clojure.pprint :as pprint]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Exit codes (kaocha.runner, kaocha.api, kaocha.config)

(def exit-codes
  "Kaocha's early-exit codes, as the process sees them."
  {:cli-error 255          ; kaocha.runner/run returns -1
   :unknown-suite 254      ; kaocha.runner/run returns -2
   :config-error 250       ; kaocha.config/replace-by-default
   :no-tests 0})           ; kaocha.api/run warns and exits 0

(def ^:private drain-timeout-ms
  "How long workers get to exit after the run before they are killed."
  5000)

(def ^:private give-up-ms
  "How long after the kills the loop waits for exits before ending anyway."
  5000)

;; ---------------------------------------------------------------------------
;; Preflight

(defn- pprint-str [x]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint x))))

(defn- plan-namespaces
  "Every namespace entry of the plan, with its suite id as `:suite`."
  [plan]
  (into [] (mapcat (fn [s] (map #(assoc % :suite (:id s)) (:namespaces s)))) (:suites plan)))

(defn- warning-lines [ws] (mapv #(str "WARNING: " %) ws))

(defn- no-tests-warning [plan]
  (if (some (comp seq :load-errors) (:suites plan))
    nil
    "No tests were found. This may be an issue in your test configuration. To investigate, check the :test-paths and :ns-patterns keys in tests.edn."))

(defn preflight
  "Decide what happens before any worker starts. Takes
  `{:cli parsed-args :config normalized :read-error str-or-nil
    :config-missing? bool :config-file path :plan plan-or-nil
    :help str :version str}`
  (the plan may be absent when it is not needed yet).

  Returns `{:effects [...]}`; when the effects end with an `:exit` the run
  is over, otherwise the caller goes on to `init`. Follows the order of
  `kaocha.runner`: config errors (Kaocha validates while loading, before it
  looks at the flags), then CLI errors, help, version, print-config,
  print-test-plan, unknown suites, and finally the `kaocha.api/run` check
  for an empty plan."
  [{:keys [cli config read-error config-missing? config-file plan help version]}]
  (let [options (:options cli)
        out (fn [lines] {:effect :print :lines (vec lines) :stream :out})
        err (fn [lines] {:effect :print :lines (vec lines) :stream :err})
        exit (fn [code] {:effect :exit :code code})
        missing-warning (when config-missing?
                          [(str "Did not load a configuration file and using the defaults. "
                                "To create one, put '#kaocha/v1 {}' in " config-file ".")])]
    {:effects
     (cond
       read-error
       [(err [(str "ERROR: " read-error)]) (exit (:config-error exit-codes))]

       (seq (:errors config))
       [(err (map #(str "ERROR: Invalid configuration: " %) (:errors config)))
        (exit (:config-error exit-codes))]

       (seq (:errors cli))
       [(out (concat (:errors cli) [help])) (exit (:cli-error exit-codes))]

       (or (:help options) (:test-help options))
       [(out [help]) (exit 0)]

       (:version options)
       [(out [version]) (exit 0)]

       (:print-config options)
       [(out [(str/trimr (pprint-str (dissoc config :warnings :errors)))]) (exit 0)]

       (:print-test-plan options)
       [(out [(str/trimr (pprint-str plan))]) (exit 0)]

       (seq (:errors plan))
       [(out (:errors plan)) (exit (:unknown-suite exit-codes))]

       :else
       (let [warnings (concat missing-warning (:warnings config) (:warnings plan))
             nothing? (and plan (empty? (plan-namespaces plan)))
             no-tests (when nothing? (no-tests-warning plan))]
         (cond-> []
           (seq warnings) (conj (err (warning-lines warnings)))
           no-tests (conj (err (warning-lines [no-tests])) (exit (:no-tests exit-codes))))))}))

;; ---------------------------------------------------------------------------
;; State

(defn- first-by-ns
  "Plan namespaces, one per name: when suites overlap the first suite's
  entry (and selection) wins."
  [plan]
  (vals (reduce (fn [m entry] (if (contains? m (:ns entry)) m (assoc m (:ns entry) entry)))
                (array-map)
                (plan-namespaces plan))))

(defn init
  "Initial loop state.

  * `plan`  from `brisk.select/plan`
  * `opts`  `{:workers n :fail-fast? b :isolate? b :capture-output? b
              :reporter :live|:ci|:documentation|:result :width n :color? b
              :durations {ns ms} :started-at ms :first-worker-id n}`

  `:first-worker-id` (default 1) is where this run's worker ids start.
  `:configure` is a `{:op :configure}` message sent to each worker right
  after it is spawned (`brisk.hooks/configure-message`). `:post-summary`
  is `{:hooks [entry] :config normalized-config}`: the hooks to run in the
  parent after the summary.
  Events from any worker this run did not spawn are dropped.

  Returns `{:state s :effects [...]}`: the first spawns, and the load errors
  of unreadable test files, which are printed in CI mode at the end."
  [plan {:keys [durations started-at] :as opts}]
  (let [entries (first-by-ns plan)
        {serial true parallel false} (group-by (comp boolean :serial?) entries)
        nses (into {} (map-indexed (fn [i e] [(:ns e) (assoc e :run-id (inc i))])) entries)
        file-errors (for [s (:suites plan) e (:load-errors s)]
                      {:event :file/error :file (:file e) :error (:error e) :at started-at})
        run (reduce rs/apply-event
                    (rs/init {:namespaces (keys nses) :started-at started-at})
                    file-errors)
        state {:sched (sched/init {:parallel (mapv :ns parallel) :serial (mapv :ns serial)}
                                  (-> (select-keys opts [:workers :fail-fast? :isolate? :durations])
                                      (assoc :first-id (:first-worker-id opts 1))))
               :run run
               :shown nil
               :nses nses
               :opts opts
               :durations (or durations {})
               :phase :running
               :stopping #{}
               :spawned #{}
               :silenced #{}
               :interrupted? false
               :drain-at nil
               :killed-at nil
               :now started-at}]
    {:state state :effects []}))

;; ---------------------------------------------------------------------------
;; Scheduling decisions

(defn- run-message [{:keys [nses opts]} ns-sym]
  (let [{:keys [select run-id suite]} (get nses ns-sym)]
    (cond-> {:op :run :ns ns-sym :run-id run-id :select select
             :capture-output? (boolean (:capture-output? opts true))}
      suite (assoc :suite suite)
      (:fail-fast? opts) (assoc :fail-fast? true))))

(defn- decide
  "Ask the scheduler for spawns, assignments and shutdowns."
  [state]
  (let [[sch spawned] (sched/drain-spawns (:sched state))
        [sch assigns] (sched/next-assignments sch)
        [sch shut] (sched/drain-shutdowns sch)
        state (-> state
                  (assoc :sched sch)
                  (update :stopping into shut)
                  (update :spawned into spawned))]
    [state
     (concat (mapcat (fn [id]
                       (cons {:effect :spawn :worker id}
                             (when-let [msg (get-in state [:opts :configure])]
                               [{:effect :send :worker id :msg msg}])))
                     spawned)
             (map (fn [[w ns-sym]] {:effect :send :worker w :msg (run-message state ns-sym)}) assigns)
             (map (fn [id] {:effect :stop :worker id}) shut))]))

;; ---------------------------------------------------------------------------
;; Output

(defn- render-opts [{:keys [opts now]} final?]
  {:width (:width opts 80) :color? (boolean (:color? opts)) :now now :final? final?})

(defn- mode [state] (get-in state [:opts :reporter] :ci))

(defn- print-effect [lines]
  (when (seq lines) [{:effect :print :lines (vec lines) :stream :out}]))

(defn- show*
  "Append-only output for what finished since `prev`."
  [state prev run ropts]
  [(assoc state :shown run)
   (print-effect (if (= :documentation (mode state))
                   (render/documentation-lines prev run ropts)
                   (render/ci-lines prev run ropts)))])

(defn- show
  "Effects that bring the screen up to date with the run state, and the
  state with `:shown` advanced. The live view redraws on ticks only; the
  append-only reporters print when a namespace has finished, which only
  an `:ns/end` (real or synthetic) can cause."
  [state {:keys [event]}]
  (let [prev (:shown state)
        run (:run state)
        ropts (render-opts state false)
        tick? (= :tick event)]
    (case (mode state)
      :result [state []]
      (:documentation :ci) (if (contains? #{:ns/end :worker/exit} event)
                             (show* state prev run ropts)
                             [state []])
      :live (if tick?
              [(assoc state :shown run)
               [{:effect :redraw :frame (render/live-frame prev run ropts)}]]
              [state []])
      [state []])))

(defn- final-output
  "Everything printed once the run is over."
  [state]
  (let [prev (:shown state)
        run (:run state)
        ropts (render-opts state true)
        nonblank (fn [s] (when-not (str/blank? s) [s]))
        summary (render/summary run ropts)]
    (case (mode state)
      :result (print-effect [(binding [*print-namespace-maps* false] (pr-str (rs/result run)))])
      :live (concat [{:effect :redraw :frame (render/live-frame prev run ropts)}]
                    (print-effect (concat (nonblank (render/failure-report run ropts)) [summary])))
      ;; Each namespace block ends with a blank line, so the summary's own
      ;; leading one would double it.
      :documentation (let [report (nonblank (render/failure-report run ropts))]
                       (print-effect (concat (render/documentation-lines prev run ropts)
                                             report
                                             [(cond-> summary
                                                (empty? report) (str/replace-first #"^\n" ""))])))
      (print-effect (concat (render/ci-lines prev run ropts)
                            (nonblank (render/orphan-report run ropts))
                            [summary])))))

;; ---------------------------------------------------------------------------
;; Finishing

(defn- not-run-reason [sch]
  (cond
    (sched/stopped? sch) :fail-fast
    :else :no-workers))

(defn- begin-drain
  "The scheduler is done: record what never ran and stop every worker."
  [state]
  (let [sch (:sched state)
        leftover (sched/pending sch)
        live (remove (:stopping state) (sched/live-workers sch))
        state (cond-> (assoc state :phase :draining :drain-at (:now state))
                (seq leftover) (update :run rs/apply-event
                                       {:event :run/not-run :namespaces leftover
                                        :reason (not-run-reason sch) :at (:now state)})
                true (update :stopping into live))]
    [state (map (fn [id] {:effect :stop :worker id}) live)]))

(defn- timings
  "Durations to remember for the next run: this run's over the old ones."
  [state]
  (into (:durations state)
        (keep (fn [[ns rec]]
                (let [d (or (:duration-ms rec)
                            (when (and (:start-at rec) (:end-at rec)) (- (:end-at rec) (:start-at rec))))]
                  (when (number? d) [ns (long (Math/round (double d)))]))))
        (get-in state [:run :namespaces])))

(defn- post-summary
  "The `post-summary` hooks run in the parent once the summary is out."
  [{:keys [opts nses run]}]
  (when-let [hs (seq (get-in opts [:post-summary :hooks]))]
    [{:effect :post-summary :hooks (vec hs)
      :result (hooks/kaocha-result (get-in opts [:post-summary :config])
                                   (fn [ns] (get-in nses [ns :suite]))
                                   run)}]))

(defn- finish [state]
  [(assoc state :phase :done)
   (concat (final-output state)
           (post-summary state)
           [{:effect :save-timings :timings (timings state)}
            {:effect :exit :code (rs/exit-code (:run state))}])])

(defn- drain-timers
  "Kill workers that ignore the shutdown, and give up on ones that never
  report their exit."
  [state]
  (let [{:keys [now drain-at killed-at]} state
        live (sched/live-workers (:sched state))]
    (cond
      (empty? live) (finish state)
      (and killed-at (>= (- now killed-at) give-up-ms)) (finish state)
      (and (nil? killed-at) (>= (- now drain-at) drain-timeout-ms))
      [(assoc state :killed-at now) (map (fn [id] {:effect :kill :worker id}) live)]
      :else [state []])))

;; ---------------------------------------------------------------------------
;; Step

(defn- fold-event
  "Feed a pool event to the scheduler and the run state. A worker that dies
  holding a namespace gets the scheduler's synthetic crash events first."
  [state ev]
  (let [crash (sched/crash-events (:sched state) ev)]
    (-> state
        (update :sched sched/handle-event ev)
        (update :run #(reduce rs/apply-event % (conj (vec crash) ev))))))

(defn- interrupt
  "Fail-fast, at the first failure: kill every other busy worker now
  instead of letting its namespace run to the end, and record those
  namespaces as interrupted. The failing worker (`worker` of the event that
  failed) finishes its own namespace; it stops after the failing var."
  [state {:keys [worker]}]
  (if (or (:interrupted? state) (not (sched/stopped? (:sched state))))
    [state []]
    (let [[sch taken] (sched/interrupt (:sched state) worker)
          ids (map first taken)
          state (cond-> (-> state
                            (assoc :sched sch :interrupted? true)
                            (update :stopping into ids)
                            (update :silenced into ids))
                  (seq taken) (update :run rs/apply-event
                                      {:event :run/interrupted :namespaces (mapv second taken)
                                       :reason :fail-fast :at (:now state)}))]
      [state (map (fn [id] {:effect :kill :worker id}) ids)])))

(defn- advance-clock [state at]
  (cond-> state (number? at) (update :now #(if % (max % at) at))))

(defn- foreign?
  "True for an event to ignore: from a worker this run never spawned (a late
  event of an earlier, cancelled run in watch mode), or anything but the
  exit of a worker fail-fast killed."
  [state {:keys [event worker]}]
  (and (not= :tick event)
       (some? worker)
       (or (not (contains? (:spawned state) worker))
           ;; killed by fail-fast: only its exit still matters
           (and (contains? (:silenced state) worker) (not= :worker/exit event)))))

(defn next-worker-id
  "The first worker id a later run should use, so ids stay unique across
  runs."
  [state]
  (sched/next-id (:sched state)))

(defn step
  "Fold one input into the loop state. Returns `{:state :effects}`. Once an
  `:exit` effect has been returned the state is `:done` and further inputs
  are ignored, as are events from workers this run did not spawn."
  [state {:keys [event at] :as input}]
  (if (or (= :done (:phase state)) (foreign? state input))
    {:state state :effects []}
    (let [tick? (= :tick event)
          state (cond-> (advance-clock state at)
                  tick? (update :run rs/apply-event {:event :tick :at at})
                  (not tick?) (fold-event input))
          [state e0] (if (and (= :running (:phase state)) (not tick?)) (interrupt state input) [state []])
          [state e1] (if (= :running (:phase state)) (decide state) [state []])
          [state e2] (show state input)
          [state e3] (if (and (= :running (:phase state)) (sched/done? (:sched state)))
                       (begin-drain state)
                       [state []])
          [state e4] (if (= :draining (:phase state)) (drain-timers state) [state []])]
      {:state state :effects (vec (concat e0 e1 e2 e3 e4))})))

(defn start
  "The first step: spawn workers, or finish at once for an empty plan."
  [state]
  (step state {:event :tick :at (:now state)}))

(defn done?
  "True once the loop has produced its `:exit` effect."
  [state]
  (= :done (:phase state)))

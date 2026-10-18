(ns brisk.engine
  "The parent's event loop as a pure state machine.

  `(step state input)` returns `{:state state' :effects [...]}`. Inputs are
  the flat events the pool puts on the queue (worker events, `:output`,
  `:worker/exit`) and timer ticks `{:event :tick :at ms}`, which may carry
  `:width`, the terminal's current width, for the live view. Effects are data
  for `brisk.main` to carry out:

      {:effect :spawn :worker id}
      {:effect :send :worker id :msg m}
      {:effect :stop :worker id}               ; ask a worker to shut down
      {:effect :kill :worker id}               ; it did not exit in time
      {:effect :print :lines [str] :stream :out|:err}
      {:effect :print-data :value v :pretty? bool}   ; EDN on stdout
      {:effect :redraw :frame {:emit [str] :frame [str]}}
      {:effect :save-timings :timings {ns millis}}
      {:effect :write-file :path str :text str}   ; the junit-xml report
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
   [brisk.junit :as junit]
   [brisk.render :as render]
   [brisk.run-state :as rs]
   [brisk.schedule :as sched]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Exit codes (kaocha.runner, kaocha.api, kaocha.config)

(def exit-codes
  "Kaocha's early-exit codes, as the process sees them."
  {:cli-error 255          ; kaocha.runner/run returns -1
   :unknown-suite 254      ; kaocha.runner/run returns -2
   :config-invalid 252     ; kaocha.runner: the config fails Kaocha's spec
   :config-error 250       ; kaocha.config/replace-by-default
   :plugin-error 248       ; kaocha.plugin/register: a plugin that cannot load
   :no-tests 0})           ; kaocha.api/run warns and exits 0

(def ^:private drain-timeout-ms
  "How long workers get to exit after the run before they are killed."
  5000)

(def ^:private give-up-ms
  "How long after the kills the loop waits for exits before ending anyway."
  5000)

(def ^:private default-watchdog-ms
  "How long a busy worker whose last protocol line could not be read may
  stay silent before it is killed. The lost line may have been the
  `:ns/end`, and then nothing else would ever arrive."
  10000)

;; ---------------------------------------------------------------------------
;; Preflight

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
    :plugin-error str-or-nil :config-missing? bool :config-file path :plan plan-or-nil
    :help str :version str}`
  (the plan may be absent when it is not needed yet).

  Returns `{:effects [...]}`; when the effects end with an `:exit` the run
  is over, otherwise the caller goes on to `init`. Follows the order of
  `kaocha.runner`: config errors (Kaocha validates while loading, before it
  looks at the flags), then CLI errors, help, version, print-config,
  print-test-plan, unknown suites, and finally the `kaocha.api/run` check
  for an empty plan."
  [{:keys [cli config read-error plugin-error config-missing? config-file plan help version]}]
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

       (seq (:invalid config))
       [(err (map #(str "ERROR: Invalid configuration file: " %) (:invalid config)))
        (exit (:config-invalid exit-codes))]

       (seq (:errors config))
       [(err (map #(str "ERROR: Invalid configuration: " %) (:errors config)))
        (exit (:config-error exit-codes))]

       plugin-error
       [(err [(str "ERROR: " plugin-error)]) (exit (:plugin-error exit-codes))]

       (seq (:errors cli))
       [(out (concat (:errors cli) [help])) (exit (:cli-error exit-codes))]

       (or (:help options) (:test-help options))
       [(out [help]) (exit 0)]

       (:version options)
       [(out [version]) (exit 0)]

       (:print-config options)
       [{:effect :print-data :value (dissoc config :warnings :errors) :pretty? true} (exit 0)]

       (:print-test-plan options)
       [{:effect :print-data :value plan :pretty? true} (exit 0)]

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

(defn- preload-list
  "What each worker requires before its first test, `[{:ns sym :suite id}]`
  in plan order, each namespace once: the plan's `:preload`, and with
  `preload?` every planned namespace."
  [plan entries preload?]
  (->> (concat (:preload plan) (when preload? (map #(select-keys % [:ns :suite]) entries)))
       (reduce (fn [[seen out] e] (if (seen (:ns e)) [seen out] [(conj seen (:ns e)) (conj out e)]))
               [#{} []])
       second))

(defn init
  "Initial loop state.

  * `plan`  from `brisk.select/plan`
  * `opts`  `{:workers n :fail-fast? b :isolate? b :preload? b :capture-output? b
              :reporter :live|:ci|:documentation|:result :width n :color? b
              :durations {ns ms} :started-at ms :first-worker-id n}`

  `:first-worker-id` (default 1) is where this run's worker ids start.
  `:watchdog-ms` (default 10000) is how long a busy worker may stay silent
  after a protocol line that could not be read before it is killed.
  `:configure` is a `{:op :configure}` message sent to each worker right
  after it is spawned (`brisk.hooks/configure-message`). `:post-summary`
  is `{:hooks [entry] :config normalized-config}`: the hooks to run in the
  parent after the summary. `:junit-xml` is the config's `:junit-xml` plus
  `:timestamp` and `:cwd`: the report is written after the summary.
  `:bindings` (`{var-sym value}`, Kaocha's `:kaocha/bindings`) goes to the
  workers with every run message.
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
               :dirty #{}
               :nses nses
               :preload (preload-list plan entries (:preload? opts))
               :junit-suites (mapv (fn [s] {:id (:id s)
                                            :namespaces (mapv :ns (:namespaces s))
                                            :files (mapv :file (:load-errors s))})
                                   (:suites plan))
               :warned #{}
               :opts opts
               :durations (or durations {})
               :phase :running
               :stopping #{}
               :spawned #{}
               :silenced #{}
               :interrupted? false
               :suspect {}
               :watchdog-killed #{}
               :notices []
               :still {}
               :drain-at nil
               :killed-at nil
               :now started-at}]
    {:state state :effects []}))

;; ---------------------------------------------------------------------------
;; Scheduling decisions

(defn- run-message [{:keys [nses opts preload]} ns-sym]
  (let [{:keys [select run-id suite]} (get nses ns-sym)]
    (cond-> {:op :run :ns ns-sym :run-id run-id :select select
             :capture-output? (boolean (:capture-output? opts true))}
      suite (assoc :suite suite)
      (seq preload) (assoc :preload preload)
      (seq (:bindings opts)) (assoc :bindings (:bindings opts))
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
  (cond-> {:width (:width opts 80) :color? (boolean (:color? opts)) :now now :final? final?}
    (:invocation opts) (assoc :invocation (:invocation opts))))

(defn- mode [state] (get-in state [:opts :reporter] :ci))

(defn- print-effect [lines]
  (when (seq lines) [{:effect :print :lines (vec lines) :stream :out}]))

(defn- shown
  "The state once `run` has been rendered: nothing is dirty any more."
  [state run]
  (assoc state :shown run :dirty #{}))

(defn- show*
  "Append-only output for what finished since `prev`."
  [state prev run ropts]
  [(shown state run)
   (print-effect (if (= :documentation (mode state))
                   (render/documentation-lines prev run ropts)
                   (render/ci-lines prev run ropts)))])

(defn- notice
  "What to show as soon as `ev` arrives, whatever the reporter, as
  `{:stream :out|:err :line str}` entries: a protocol line that could not
  be read, a worker's warning (each text once per run, since every worker
  may send it), and output a test printed that was not captured
  (`--no-capture-output`, load-time prints, JVM warnings), as Kaocha
  prints it as it happens. Output a worker rescued from a test that
  exited (`:captured?`) belongs to that test's failure report only."
  [state {:keys [protocol-error worker message text captured?] :as ev}]
  (cond
    (and (= :output (:event ev)) protocol-error)
    [{:stream :err
      :line (render/protocol-notice {:worker worker
                                     :ns (sched/holding (:sched state) worker)
                                     :reason protocol-error}
                                    (render-opts state false))}]
    (= :output (:event ev))
    (when-not captured? [{:stream :out :text true :line (str text)}])
    (and (= :worker/warning (:event ev)) (not (contains? (:warned state) message)))
    [{:stream :err :line (str "WARNING: " message)}]))

(defn- flush-notices
  "Append-only reporters print notices at once (test output on stdout,
  except with `--print-result`, whose stdout is one EDN map); the live
  view shows them above the frame at its next redraw."
  [state]
  (let [entries (:notices state)
        result? (= :result (mode state))]
    (if (or (empty? entries) (= :live (mode state)))
      [state []]
      [(assoc state :notices [])
       (for [run (partition-by :stream entries)]
         {:effect :print :lines (mapv :line run)
          :stream (if result? :err (:stream (first run)))})])))

(def ^:private still-running-ms
  "In CI and documentation mode, how long a test may run before a line says
  it still is, and how often that line repeats."
  30000)

(defn- still-running
  "On a tick in the append-only modes: a line for each test (or namespace
  between tests) running for `still-running-ms` since it started or since
  its last such line, so a hung test does not leave the log silent."
  [state]
  (if-not (contains? #{:ci :documentation} (mode state))
    [state []]
    (let [now (:now state)
          due (for [{:keys [worker ns var ns-at var-at]} (rs/running (:run state))
                    :let [since (or (when var var-at) ns-at)
                          k [worker ns var since]
                          last (get-in state [:still k] since)]
                    :when (and (number? now) (number? last) (>= (- now last) still-running-ms))]
                [k {:ns ns :var var :elapsed (- now since)}])]
      (if (empty? due)
        [state []]
        [(update state :still into (map (fn [[k _]] [k now])) due)
         [{:effect :print :stream :out
           :lines (mapv #(render/still-running-line (second %) (render-opts state false)) due)}]]))))

(defn- with-notices [state frame]
  (let [ropts (render-opts state false)
        lines (mapcat (fn [{:keys [text line]}] (if text (render/stray-lines line ropts) [line]))
                      (:notices state))]
    [(assoc state :notices []) (update frame :emit #(into (vec lines) %))]))

(defn- show
  "Effects that bring the screen up to date with the run state, and the
  state with `:shown` advanced. The live view redraws on ticks only; the
  append-only reporters print when a namespace has finished, which only
  an `:ns/end` (real or synthetic) can cause."
  [state {:keys [event]}]
  (let [prev (:shown state)
        run (:run state)
        ropts (assoc (render-opts state false) :only (:dirty state))
        tick? (= :tick event)
        [state notices] (flush-notices state)
        [state effects]
        (case (mode state)
          :result [state []]
          (:documentation :ci) (if (contains? #{:ns/end :worker/exit} event)
                                 (show* state prev run ropts)
                                 [state []])
          :live (if tick?
                  (let [[state frame] (with-notices state (render/live-frame prev run ropts))]
                    [(shown state run) [{:effect :redraw :frame frame}]])
                  [state []])
          [state []])]
    [state (concat notices effects)]))

(defn- final-output
  "Everything printed once the run is over."
  [state]
  (let [prev (:shown state)
        run (:run state)
        ropts (assoc (render-opts state true) :only (:dirty state))
        nonblank (fn [s] (when-not (str/blank? s) [s]))
        summary (render/summary run ropts)]
    (case (mode state)
      :result [{:effect :print-data :value (rs/result run)}]
      :live (concat [{:effect :redraw :frame (second (with-notices state (render/live-frame prev run ropts)))}]
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

(defn- junit-xml
  "The JUnit XML report, when `kaocha.plugin/junit-xml` names a file."
  [{:keys [opts run junit-suites]}]
  (when-let [{:keys [target-file] :as jx} (:junit-xml opts)]
    [{:effect :write-file :path target-file
      :text (junit/report run junit-suites jx)}]))

(defn- finish [state]
  [(assoc state :phase :done)
   (concat (final-output state)
           (junit-xml state)
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

(defn- watch-protocol
  "Track busy workers whose last protocol line could not be read. Any
  event that did decode clears the suspicion."
  [state {:keys [event worker protocol-error at]}]
  (cond
    (nil? worker) state
    (and (= :output event) protocol-error (sched/holding (:sched state) worker))
    (assoc-in state [:suspect worker] at)
    (= :output event) state
    :else (update state :suspect dissoc worker)))

(defn- watchdog
  "Kill a suspect worker that has said nothing readable for the watchdog
  period while holding a namespace; its exit then reports as a crash."
  [state]
  (let [limit (get-in state [:opts :watchdog-ms] default-watchdog-ms)
        now (:now state)
        due (->> (:suspect state)
                 (filter (fn [[w at]] (and (number? at) (number? now) (>= (- now at) limit)
                                           (sched/holding (:sched state) w))))
                 (map key)
                 (remove (:watchdog-killed state))
                 sort)]
    [(-> state
         (update :watchdog-killed into due)
         (update :suspect #(apply dissoc % due)))
     (map (fn [w] {:effect :kill :worker w}) due)]))

(defn- fold-event
  "Feed a pool event to the scheduler and the run state. A worker that dies
  holding a namespace gets the scheduler's synthetic crash events first."
  [state ev]
  (let [ev (cond-> ev
             (and (= :worker/exit (:event ev)) (contains? (:watchdog-killed state) (:worker ev)))
             (assoc :reason "killed: its protocol output could not be read and it went silent"))
        crash (sched/crash-events (:sched state) ev)
        held (when (= :output (:event ev)) (get-in state [:run :workers (:worker ev) :ns]))]
    (-> state
        (update :dirty into (keep :ns) (cond-> (conj (vec crash) ev) held (conj {:ns held})))
        (update :notices into (notice state ev))
        (cond-> (= :worker/warning (:event ev)) (update :warned conj (:message ev)))
        (watch-protocol ev)
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
                            (update :dirty into (map second taken))
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
                  (and tick? (:width input)) (assoc-in [:opts :width] (:width input))
                  tick? (update :run rs/apply-event {:event :tick :at at})
                  (not tick?) (fold-event input))
          [state e0] (if (and (= :running (:phase state)) (not tick?)) (interrupt state input) [state []])
          [state ew] (if tick? (watchdog state) [state []])
          [state es] (if tick? (still-running state) [state []])
          [state e1] (if (= :running (:phase state)) (decide state) [state []])
          [state e2] (show state input)
          [state e3] (if (and (= :running (:phase state)) (sched/done? (:sched state)))
                       (begin-drain state)
                       [state []])
          [state e4] (if (= :draining (:phase state)) (drain-timers state) [state []])]
      {:state state :effects (vec (concat e0 ew es e1 e2 e3 e4))})))

(defn start
  "The first step: spawn workers, or finish at once for an empty plan."
  [state]
  (step state {:event :tick :at (:now state)}))

(defn done?
  "True once the loop has produced its `:exit` effect."
  [state]
  (= :done (:phase state)))

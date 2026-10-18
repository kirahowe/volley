(ns brisk.main
  "Entry point: `bb -m brisk.main [OPTIONS]... [TEST-SUITE]...`.

  Shell only. It gathers the inputs (argv, tests.edn, test files, the
  environment, the terminal), hands them to the pure core, and carries out
  the effects `brisk.engine` returns. The one mutable object is the event
  queue; the worker handles and the live view's frame height are a value
  (`world`) threaded through the loop with the engine's state."
  (:require
   [brisk.cli :as cli]
   [brisk.config :as config]
   [brisk.discover :as discover]
   [brisk.engine :as engine]
   [brisk.hooks :as hooks]
   [brisk.select :as select]
   [brisk.shell.fs :as bfs]
   [brisk.shell.hooks :as shell-hooks]
   [brisk.shell.pool :as pool]
   [brisk.shell.runtime :as runtime]
   [brisk.shell.terminal :as term]
   [brisk.shell.watch :as watch-loop]
   [clojure.pprint :as pprint]
   [clojure.string :as str])
  (:import
   (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(set! *warn-on-reflection* true)

(def version "brisk 0.1.0-alpha")

(def ^:private tick-ms 80)

;; ---------------------------------------------------------------------------
;; Inputs

(defn- load-config
  "Parse args and read tests.edn. Returns the pieces `engine/preflight`
  needs."
  [args env]
  (let [parsed (cli/parse-args args)
        options (:options parsed)
        file (:config-file options "tests.edn")
        missing? (not (bfs/exists? file))
        text (when-not missing? (bfs/read-file file))
        read (config/read-config text (cond-> (assoc (select-keys env [:env :props :hostname :user])
                                                     :include (bfs/include-resolver file))
                                        (:profile options) (assoc :profile (:profile options))))
        cfg (config/normalize (:config read) options env)
        parsed (update parsed :errors into (cli/plugin-flag-errors options (:plugins cfg)))]
    {:cli parsed
     :config (update cfg :warnings into (:warnings read))
     :read-error (:error read)
     :config-missing? missing?
     :config-file file}))

(defn- discover-all
  "Namespace info for every Clojure file under the suites' test paths."
  [cfg]
  (->> (:tests cfg)
       (mapcat :test-paths)
       bfs/clojure-files
       (mapv #(discover/ns-info % (bfs/read-file %)))))

(defn- local-timestamp
  "Now as an ISO local date-time to the second, as kaocha-junit-xml writes
  its timestamps."
  []
  (.format java.time.format.DateTimeFormatter/ISO_LOCAL_DATE_TIME
           (.truncatedTo (java.time.LocalDateTime/now) java.time.temporal.ChronoUnit/SECONDS)))

(defn- reporter
  "`:live` needs a terminal; anything else gets the append-only CI output.
  `--print-result` replaces the reporter with one EDN map at the end, as
  Kaocha's does."
  [cfg cli-options tty?]
  (cond
    (:print-result cli-options) :result
    (= :documentation (:reporter cfg)) :documentation
    tty? :live
    :else :ci))

(defn- color?
  "Colour when the config allows it, NO_COLOR is unset, and stdout is a
  terminal or `--color` was given explicitly."
  [cfg cli-options env tty?]
  (and (:color? cfg)
       (str/blank? (get-in env [:env "NO_COLOR"]))
       (or tty? (true? (:color cli-options)))))

;; ---------------------------------------------------------------------------
;; Effects

(defn- print-lines! [{:keys [lines stream]}]
  (let [text (str (str/join "\n" lines) "\n")]
    (if (= :err stream)
      (binding [*out* *err*] (print text) (flush))
      (do (print text) (flush)))))

(defn- add-test-paths!
  "On babashka, put the suites' test paths on this process's classpath so
  that parent-side hooks in test namespaces resolve (Kaocha adds them the
  same way). The JVM classpath is fixed; there they must already be on it."
  [paths]
  (when-let [add (try (requiring-resolve 'babashka.classpath/add-classpath)
                      (catch Exception _ nil))]
    (doseq [p paths] (add p))))

(defn- post-summary!
  "Run the parent's `post-summary` hooks; a failure is printed, not fatal."
  [{:keys [hooks result]}]
  (when-let [e (shell-hooks/post-summary! hooks result)]
    (print-lines! {:lines [(str "WARNING: post-summary hook failed: " (ex-message e))
                           (str "  " (some-> (ex-cause e) ex-message))]
                   :stream :err})))

(defn- print-data!
  "Print a data effect's `:value`: pretty-printed for `--print-config` and
  `--print-test-plan`, on one line for `--print-result`. The print vars are
  bound here, in the shell, so the core stays free of dynamic bindings."
  [{:keys [value pretty?]}]
  (binding [*print-namespace-maps* false *print-length* nil *print-level* nil]
    (print-lines! {:lines [(if pretty?
                             (str/trimr (with-out-str (pprint/pprint value)))
                             (pr-str value))]
                   :stream :out})))

(defn- run-effects!
  "Carry out `effects` against `world`, `{:handles {id handle} :height n}`:
  the worker handles and the live frame's height, threaded as a value.
  `ctx` holds the queue and the worker command. Returns `[world code]`,
  code being the exit code when an `:exit` effect was among them."
  [{:keys [queue command]} world effects]
  (reduce
   (fn [[world _] {:keys [effect worker msg frame timings code] :as e}]
     (let [handle (get-in world [:handles worker])
           world (case effect
                   :spawn (assoc-in world [:handles worker]
                                    (pool/start-worker! {:queue queue :command command} worker))
                   :send (do (some-> handle (pool/send! msg)) world)
                   :stop (do (some-> handle pool/stop!) world)
                   :kill (do (some-> handle pool/kill!) world)
                   :print (do (print-lines! e) world)
                   :print-data (do (print-data! e) world)
                   :redraw (assoc world :height (term/redraw! (:height world 0) frame))
                   :save-timings (do (bfs/write-timings! timings) world)
                   :write-file (do (when-let [err (bfs/write-file! (:path e) (:text e))]
                                     (print-lines! {:lines [(str "WARNING: could not write " (:path e) ": " err)]
                                                    :stream :err}))
                                   world)
                   :post-summary (do (post-summary! e) world)
                   :exit world)]
       (if (= :exit effect) (reduced [world code]) [world nil])))
   [world nil]
   effects))

(def ^:private no-workers {:handles {} :height 0})

(defn- step!
  "Feed one input to the engine and carry out what it returns. A worker the
  pool reported as exited loses its handle. Returns `[world state code]`.
  Watch mode drives its runs with this too."
  [ctx world state {:keys [event worker] :as input}]
  (let [world (cond-> world (= :worker/exit event) (update :handles dissoc worker))
        {state :state effects :effects} (engine/step state input)
        [world code] (run-effects! ctx world effects)]
    [world state code]))

(defn- event-loop!
  "Feed queue events and ticks to the engine until it exits. In live mode
  (`:live?` in `ctx`) a tick carries the terminal width, read at most every
  `brisk.shell.terminal/width-check-ms`, so the frame follows a resize.
  Returns the exit code."
  [ctx world state]
  (let [^LinkedBlockingQueue queue (:queue ctx)]
    (loop [world world state state last-tick (System/currentTimeMillis) last-width 0]
      (let [now (System/currentTimeMillis)
            wait (max 0 (- (+ last-tick tick-ms) now))
            ev (.poll queue wait TimeUnit/MILLISECONDS)
            now (System/currentTimeMillis)
            tick? (>= (- now last-tick) tick-ms)
            width? (and tick? (:live? ctx) (>= (- now last-width) term/width-check-ms))
            inputs (cond-> []
                     ev (conj ev)
                     tick? (conj (cond-> {:event :tick :at now} width? (assoc :width (term/width)))))
            [world state code] (reduce (fn [[world state _] input]
                                         (let [[world state code] (step! ctx world state input)]
                                           (if code (reduced [world state code]) [world state nil])))
                                       [world state nil]
                                       inputs)]
        (if code
          code
          (recur world state (if tick? now last-tick) (if width? now last-width)))))))

(defn- run-tests!
  "Start the engine on `plan` and run it to completion. Returns the exit
  code. Workers still alive when it ends, or when the JVM shuts down, are
  killed: they are this process's descendants, so no handle is needed."
  [plan command opts]
  (let [ctx {:queue (pool/new-queue)
             :command command
             :live? (= :live (:reporter opts))}
        hook (Thread. ^Runnable pool/kill-descendants!)
        go (fn []
             (let [{:keys [state effects]} (engine/init plan opts)
                   [world _] (run-effects! ctx no-workers effects)
                   {state :state effects :effects} (engine/start state)
                   [world code] (run-effects! ctx world effects)]
               (or code (event-loop! ctx world state))))]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      (if (= :live (:reporter opts))
        (term/with-hidden-cursor! go)
        (go))
      (finally
        (pool/kill-descendants!)
        (try (.removeShutdownHook (Runtime/getRuntime) hook) (catch IllegalStateException _ nil))))))

(defn- reload-config
  "Re-read tests.edn for watch mode: `{:config c}`, or `{:error msg}` when
  it is invalid."
  [args env]
  (let [{:keys [config read-error]} (load-config args env)
        errors (:errors config)]
    (if (or read-error (seq errors))
      {:error (or read-error (str/join "; " errors))}
      {:config config})))

(defn- watch-tests!
  "Watch mode: run `brisk.shell.watch` with this namespace's effect carrier.
  `reload` re-reads tests.edn for the loop; `worker-command` rebuilds the
  worker command for a reloaded config."
  [command cfg loaded opts reload worker-command]
  (let [ctx {:queue (pool/new-queue)
             :command command}]
    (watch-loop/watch! {:ctx ctx
                      :run-effects! run-effects!
                      :step! step!
                      :print! print-lines!
                      :reload-config reload
                      :worker-command worker-command
                      :config cfg
                      :config-file (:config-file loaded)
                      :cli-suites (:suites (:cli loaded))
                      :engine-opts opts
                      :keys? (term/stdin-tty?)
                      :live? (= :live (:reporter opts))})))

;; ---------------------------------------------------------------------------
;; Entry point

(defn run
  "Run brisk with `args` and return the exit code instead of exiting."
  [args]
  (let [env (bfs/environment)
        {:keys [cli config] :as loaded} (load-config args env)
        plan (when-not (or (:read-error loaded) (seq (:errors config)))
               (select/plan config (discover-all config) (:suites cli)))
        {:keys [effects]} (engine/preflight (assoc loaded :plan plan
                                                   :help (cli/help-text (runtime/invocation))
                                                   :version version))
        [_ code] (run-effects! {} no-workers effects)]
    (or code
        (let [tty? (term/tty?)
              mode (reporter config (:options cli) tty?)
              {:keys [command error]} (runtime/worker-command config (:aliases (:options cli)))
              opts (merge
                    {:workers (:workers config)
                     :fail-fast? (:fail-fast? config)
                     :isolate? (:isolate? config)
                     :preload? (:preload? config)
                     :invocation (runtime/invocation)
                     :bindings (:bindings config)
                     :capture-output? (:capture-output? config)
                     :reporter mode
                     :width (if (= :live mode) (term/width) 1000)
                     :color? (color? config (:options cli) env tty?)
                     :durations (bfs/read-timings)
                     :started-at (System/currentTimeMillis)}
                    (when-let [jx (:junit-xml config)]
                      {:junit-xml (assoc jx :timestamp (local-timestamp) :cwd (System/getProperty "user.dir"))})
                    (hooks/engine-opts config))
              _ (when (:post-summary opts) (add-test-paths! (mapcat :test-paths (:tests config))))]
          (cond
            error (do (print-lines! {:lines [(str "brisk: " error)] :stream :err}) 250)
            (:watch? config) (watch-tests! command config loaded opts #(reload-config args env)
                                           #(runtime/worker-command % (:aliases (:options cli))))
            :else (run-tests! plan command opts))))))

(defn -main
  "`bb -m brisk.main [OPTIONS]... [TEST-SUITE]...`"
  [& args]
  (let [code (run args)]
    (shutdown-agents)
    (System/exit code)))

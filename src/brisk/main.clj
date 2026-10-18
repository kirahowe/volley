(ns brisk.main
  "Entry point: `bb -m brisk.main [OPTIONS]... [TEST-SUITE]...`.

  Shell only. It gathers the inputs (argv, tests.edn, test files, the
  environment, the terminal), hands them to the pure core, and carries out
  the effects `brisk.engine` returns. The only mutable things are the one
  event queue, the worker process handles, and the frame height of the
  live view, all local to `run!`."
  (:require
   [brisk.cli :as cli]
   [brisk.config :as config]
   [brisk.discover :as discover]
   [brisk.engine :as engine]
   [brisk.select :as select]
   [brisk.shell.fs :as bfs]
   [brisk.shell.pool :as pool]
   [brisk.shell.runtime :as runtime]
   [brisk.shell.terminal :as term]
   [brisk.shell.watch :as watch-loop]
   [clojure.string :as str])
  (:import
   (java.util.concurrent LinkedBlockingQueue TimeUnit)))

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
        cfg (config/normalize (:config read) options env)]
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

(defn- run-effects!
  "Carry out `effects`. `ctx` holds the queue, the worker command and two
  volatiles: worker handles and the live frame height. Returns the exit
  code when an `:exit` effect was among them, else nil."
  [{:keys [queue command handles height]} effects]
  (reduce
   (fn [_ {:keys [effect worker msg frame timings code] :as e}]
     (case effect
       :spawn (vswap! handles assoc worker (pool/start-worker! {:queue queue :command command} worker))
       :send (some-> (get @handles worker) (pool/send! msg))
       :stop (some-> (get @handles worker) pool/stop!)
       :kill (some-> (get @handles worker) pool/kill!)
       :print (print-lines! e)
       :redraw (vreset! height (term/redraw! @height frame))
       :save-timings (bfs/write-timings! timings)
       :exit nil)
     (when (= :exit effect) (reduced code)))
   nil
   effects))

(defn- forget-exited!
  "Drop the handle of a worker the pool reported as exited."
  [handles {:keys [event worker]}]
  (when (= :worker/exit event) (vswap! handles dissoc worker)))

(defn- event-loop!
  "Feed queue events and ticks to the engine until it exits."
  [ctx state]
  (let [^LinkedBlockingQueue queue (:queue ctx)]
    (loop [state state last-tick (System/currentTimeMillis)]
      (let [now (System/currentTimeMillis)
            wait (max 0 (- (+ last-tick tick-ms) now))
            ev (.poll queue wait TimeUnit/MILLISECONDS)
            now (System/currentTimeMillis)
            tick? (>= (- now last-tick) tick-ms)
            inputs (cond-> []
                     ev (conj ev)
                     tick? (conj {:event :tick :at now}))
            [state code] (reduce (fn [[st _] input]
                                   (forget-exited! (:handles ctx) input)
                                   (let [{st :state effs :effects} (engine/step st input)
                                         code (run-effects! ctx effs)]
                                     (if code (reduced [st code]) [st nil])))
                                 [state nil]
                                 inputs)]
        (if code
          code
          (recur state (if tick? now last-tick)))))))

(defn- run-tests!
  "Start the engine on `plan` and run it to completion. Returns the exit
  code."
  [plan command opts]
  (let [ctx {:queue (pool/new-queue)
             :command command
             :handles (volatile! {})
             :height (volatile! 0)}
        kill-workers (fn [] (pool/kill-all! (vals @(:handles ctx))))
        hook (Thread. ^Runnable kill-workers)
        go (fn []
             (let [{:keys [state effects]} (engine/init plan opts)
                   _ (run-effects! ctx effects)
                   {state :state effects :effects} (engine/start state)]
               (or (run-effects! ctx effects)
                   (event-loop! ctx state))))]
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      (if (= :live (:reporter opts))
        (term/with-hidden-cursor! go)
        (go))
      (finally
        (kill-workers)
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
  `reload` re-reads tests.edn for the loop."
  [command cfg loaded opts reload]
  (let [ctx {:queue (pool/new-queue)
             :command command
             :handles (volatile! {})
             :height (volatile! 0)}]
    (watch-loop/watch! {:ctx ctx
                      :run-effects! run-effects!
                      :print! print-lines!
                      :reload-config reload
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
                                                   :help (cli/help-text) :version version))
        code (run-effects! {} effects)]
    (or code
        (let [tty? (term/tty?)
              mode (reporter config (:options cli) tty?)
              {:keys [command error]} (runtime/worker-command config (:aliases (:options cli)))
              opts {:workers (:workers config)
                    :fail-fast? (:fail-fast? config)
                    :isolate? (:isolate? config)
                    :capture-output? (:capture-output? config)
                    :reporter mode
                    :width (if (= :live mode) (term/width) 1000)
                    :color? (color? config (:options cli) env tty?)
                    :durations (bfs/read-timings)
                    :started-at (System/currentTimeMillis)}]
          (cond
            error (do (print-lines! {:lines [(str "brisk: " error)] :stream :err}) 250)
            (:watch? config) (watch-tests! command config loaded opts #(reload-config args env))
            :else (run-tests! plan command opts))))))

(defn -main
  "`bb -m brisk.main [OPTIONS]... [TEST-SUITE]...`"
  [& args]
  (let [code (run args)]
    (shutdown-agents)
    (System/exit code)))

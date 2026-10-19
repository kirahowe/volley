(ns volley.main
  "Entry point: `bb -m volley.main [OPTIONS]... [TEST-SUITE]...`.

  Shell only. It gathers the inputs (argv, tests.edn, test files, the
  environment, the terminal), hands them to the pure core, and carries out
  the effects `volley.engine` returns. The one mutable object is the event
  queue; the worker handles and the live view's frame height are a value
  (`world`) threaded through the loop with the engine's state."
  (:require
   [volley.cli :as cli]
   [volley.config :as config]
   [volley.discover :as discover]
   [volley.engine :as engine]
   [volley.select :as select]
   [volley.shell.fs :as bfs]
   [volley.shell.hooks :as shell-hooks]
   [volley.shell.pool :as pool]
   [volley.shell.runtime :as runtime]
   [volley.shell.terminal :as term]
   [volley.shell.watch :as watch-loop]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str])
  (:import
   (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(set! *warn-on-reflection* true)

(def version
  "What `--version` prints. The version lives in `volley/version.edn` on
  the classpath, the one place the release and the CHANGELOG follow."
  (str "volley " (:version (edn/read-string (slurp (io/resource "volley/version.edn"))))))

(def ^:private tick-ms 80)

;; ---------------------------------------------------------------------------
;; Inputs

(defn- ns-source
  "The source text of namespace `ns-sym` from this process's classpath or
  one of `dirs`, or nil when it has no source file."
  [dirs ns-sym]
  (let [base (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/"))
        files (map #(str base %) [".clj" ".cljc" ".bb"])]
    (or (some #(some-> (io/resource %) slurp) files)
        (some (fn [d] (some #(let [f (str d "/" %)] (when (bfs/exists? f) (bfs/read-file f))) files)) dirs))))

(defn- plugin-error
  "Kaocha's error for the first plugin in `:unknown-plugins` that does not
  load, or nil."
  [cfg]
  (let [dirs (mapcat (juxt :test-paths :source-paths) (:tests cfg))]
    (some #(config/plugin-load-error % (partial ns-source (flatten dirs))) (:unknown-plugins cfg))))

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
     :plugin-error (plugin-error cfg)
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

(defn- add-test-paths!
  "On babashka, put the suites' test paths on this process's classpath so
  that parent-side hooks in test namespaces resolve (Kaocha adds them the
  same way). The JVM classpath is fixed; there they must already be on it."
  [paths]
  (when-let [add (try (requiring-resolve 'babashka.classpath/add-classpath)
                      (catch Exception _ nil))]
    (doseq [p (flatten paths)] (add p))))

(defn- unresolved-reporter
  "The first of the project's reporter symbols that does not resolve in
  this process, as Kaocha resolves its reporter before running anything.
  Checked only when the workers run on this process's runtime; otherwise
  a worker that cannot resolve one fails its setup."
  [config]
  (when (and (seq (:reporters config))
             (= (:runtime config) (if (runtime/bb?) :bb :jvm)))
    (add-test-paths! (mapcat (juxt :test-paths :source-paths) (:tests config)))
    (some (fn [sym] (when-not (try (requiring-resolve sym) (catch Throwable _ nil)) sym))
          (:reporters config))))

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
  `volley.shell.terminal/width-check-ms`, so the frame follows a resize.
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
  "Watch mode: run `volley.shell.watch` with this namespace's effect carrier.
  `reload` re-reads tests.edn for the loop; `worker-command` rebuilds the
  worker command for a reloaded config, recomputing the classpath when
  asked to."
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
  "Run volley with `args` and return the exit code instead of exiting."
  [args]
  (let [;; the seed of a randomized run without --seed, as kaocha.plugin.randomize draws it
        env (assoc (bfs/environment) :seed (rand-int Integer/MAX_VALUE))
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
                    {;; the rerun hint reads the same config file
                     :invocation (cond-> (runtime/invocation)
                                   (not= "tests.edn" (:config-file loaded))
                                   (str " --config-file " (:config-file loaded)))
                     :reporter mode
                     :width (if (= :live mode) (term/width) 1000)
                     :color? (color? config (:options cli) env tty?)
                     :durations (bfs/read-timings)
                     :started-at (System/currentTimeMillis)}
                    (config/engine-opts config (bfs/run-info)))
              _ (when (:post-summary opts) (add-test-paths! (mapcat :test-paths (:tests config))))
              bad-reporter (when-not error (unresolved-reporter config))]
          (cond
            error (do (print-lines! {:lines [(str "volley: " error)] :stream :err}) 250)
            bad-reporter
            ;; kaocha.api/resolve-reporter: an early exit with -2
            (do (print-lines! {:lines [(str "ERROR: Failed to resolve reporter var: " bad-reporter)]
                               :stream :err})
                254)
            (:watch? config) (watch-tests! command config loaded opts #(reload-config args env)
                                           #(runtime/worker-command %1 (:aliases (:options cli)) {:refresh? %2}))
            :else (run-tests! plan command opts))))))

(defn -main
  "`bb -m volley.main [OPTIONS]... [TEST-SUITE]...`"
  [& args]
  (let [code (run args)]
    (shutdown-agents)
    (System/exit code)))

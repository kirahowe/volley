(ns volley.shell.watch
  "The watch-mode loop. Shell only: it polls modification times and the
  keyboard, feeds what it finds to the pure `volley.watch` state machine, and
  carries out the effects: build a plan, drive `volley.engine` for one run in
  fresh workers, cancel a run, print, show the prompt.

  Both inputs sit beside the one event queue instead of in a second one: the
  queue holds only worker events, and the loop that reads it with a timeout
  also checks the keyboard and, every `volley.watch/poll-ms`, the files. The
  queue is the one mutable object; everything else is threaded through the
  loop as a value: the worker handles and the live frame height (`:world`,
  as `volley.main` threads them for a one-shot run) and the next worker id:
  ids keep counting up
  across runs, so a late event from a cancelled run's worker belongs to no
  worker of the next run, and the engine drops it.

  `env` (from `volley.main`):
  * `:ctx`            `{:queue :command}`
  * `:run-effects!`   `(fn [ctx world effects])` carries out engine effects,
                      returns `[world exit-code-or-nil]`
  * `:step!`          `(fn [ctx world state input])` feeds the engine one
                      input and carries out its effects, `[world state code]`
  * `:print!`         `(fn [{:lines :stream}])`
  * `:reload-config`  `(fn [])` returning `{:config c}` or `{:error msg}`
  * `:worker-command` `(fn [config refresh?])` returning `{:command argv}`
                      or `{:error msg}`, for a reloaded config; with
                      `refresh?` the classpath is computed again
  * `:ctx`'s `:command` is only the first run's worker command; the session
    keeps the current one
  * `:config`, `:config-file`, `:cli-suites`
  * `:engine-opts`    options for `volley.engine/init`, without clock or timings
  * `:keys?`          read the keyboard (stdin is a terminal)
  * `:live?`          the live reporter is in use"
  (:require
   [volley.discover :as discover]
   [volley.engine :as engine]
   [volley.hooks :as hooks]
   [volley.shell.fs :as bfs]
   [volley.shell.pool :as pool]
   [volley.shell.terminal :as term]
   [volley.watch :as watch])
  (:import
   (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(set! *warn-on-reflection* true)

(def ^:private tick-ms 80)

(def ^:private cancel-wait-ms
  "How long a cancelled run's workers get to report their exit."
  5000)

(defn- now-ms [] (System/currentTimeMillis))

;; ---------------------------------------------------------------------------
;; Files

(defn- watch-dirs [config]
  (vec (distinct (mapcat (fn [s] (concat (:test-paths s) (:source-paths s))) (:tests config)))))

(defn- take-snapshot [env config]
  (bfs/mtime-snapshot (watch-dirs config) (into [(:config-file env)] watch/deps-files) watch/watched-file?))

(defn- read-infos
  "`{path ns-info}` for every Clojure file under the watched directories."
  [config]
  (into {}
        (map (fn [path] [path (discover/ns-info path (bfs/read-file path))]))
        (bfs/clojure-files (watch-dirs config))))

(defn- scan
  "Compare modification times with the last snapshot. Returns the session
  with a refreshed snapshot and graph inputs, and a `:files-changed` input
  when something changed."
  [env sess at]
  (let [snapshot (take-snapshot env (:config sess))
        ch (watch/changes (:snapshot sess) snapshot)
        paths (watch/changed-paths ch)]
    (if (empty? paths)
      [sess nil]
      (let [texts (into {} (map (fn [p] [p (bfs/read-file p)])) (concat (:added ch) (:modified ch)))
            infos (watch/update-infos (:infos sess) ch texts)]
        [(assoc sess :snapshot snapshot :infos infos)
         {:event :files-changed :paths paths :at at
          :affected (watch/affected-by (:tests (:config sess)) (:infos sess) infos ch)}]))))

(defn- read-keys!
  "All key presses waiting on stdin, as `:key` inputs."
  []
  (loop [acc []]
    (if-let [c (and (< (count acc) 32) (term/read-key!))]
      (recur (conj acc {:event :key :char c}))
      acc)))

;; ---------------------------------------------------------------------------
;; Runs

(defn- known-namespaces [sess]
  (into #{} (keep :ns) (vals (:infos sess))))

(defn- end-engine
  "Forget the run's engine, remembering where its worker ids ended."
  [sess]
  (-> sess
      (assoc :next-worker-id (engine/next-worker-id (:engine sess)))
      (assoc :engine nil)))

(defn- finish-run
  "The engine has exited: tell the UI how the run went."
  [sess]
  (-> sess
      (update :inbox conj {:event :run/finished :known (known-namespaces sess)
                           :outcome (watch/outcome (:run (:engine sess)))})
      end-engine))

(defn- engine-input [{:keys [ctx step!]} sess input]
  (if-let [state (:engine sess)]
    (let [[world state code] (step! (assoc ctx :command (:command sess)) (:world sess) state input)
          sess (assoc sess :engine state :world world)]
      (cond-> sess code finish-run))
    sess))

(defn- reload
  "Re-read tests.edn. The worker command is rebuilt from the new config,
  since its classpath includes the suites' test paths. Once deps.edn or
  bb.edn has changed, every rebuild computes the classpath again instead
  of reusing the one the parent started with (`:refresh-classpath?`
  stays set)."
  [env sess reload-deps?]
  (let [refresh? (boolean (or reload-deps? (:refresh-classpath? sess)))
        {:keys [config error]} ((:reload-config env))
        cmd (when config ((:worker-command env) config refresh?))
        error (or error (:error cmd))
        complain! #((:print! env) {:lines [(str "ERROR: " % " (keeping the previous configuration)")]
                                   :stream :err})]
    (if (and config (not error))
      (assoc sess :config config :command (:command cmd) :refresh-classpath? refresh?
             :infos (read-infos config) :snapshot (take-snapshot env config))
      (do (complain! error) sess))))

(defn- start-run [{:keys [ctx run-effects! engine-opts] :as env} sess selection]
  (let [sess (cond-> sess
               (:reload-config? selection) (as-> s (reload env s (:reload-deps? selection))))
        plan (watch/select-plan (:config sess) (:infos sess) (:cli-suites env) selection
                                (get-in sess [:ws :last :failed]))]
    (if (watch/plan-empty? plan)
      (update sess :inbox conj {:event :run/finished :empty? true :known (known-namespaces sess)})
      (let [opts (cond-> (merge engine-opts
                                (hooks/engine-opts (:config sess))
                                {:started-at (now-ms) :durations (bfs/read-timings)
                                 :first-worker-id (:next-worker-id sess)
                                 :compact-summary? true})
                   (:live? env) (assoc :width (term/width)))
            ctx (assoc ctx :command (:command sess))
            {state :state effects :effects} (engine/init plan opts)
            [world _] (run-effects! ctx (assoc (:world sess) :height 0) effects)
            {state :state effects :effects} (engine/start state)
            [world code] (run-effects! ctx world effects)]
        (cond-> (assoc sess :engine state :world world) code finish-run)))))

(defn- cancel-run
  "Kill the run's workers by handle and wait for their exit events, so none
  of their output reaches the next run."
  [{:keys [ctx]} sess]
  (let [^LinkedBlockingQueue queue (:queue ctx)
        handles (get-in sess [:world :handles])
        deadline (+ (now-ms) cancel-wait-ms)]
    (pool/kill-all! (vals handles))
    (loop [waiting (set (keys handles))]
      (when (and (seq waiting) (< (now-ms) deadline))
        (let [ev (.poll queue 100 TimeUnit/MILLISECONDS)]
          (recur (if (= :worker/exit (:event ev)) (disj waiting (:worker ev)) waiting)))))
    (cond-> (assoc-in sess [:world :handles] {}) (:engine sess) end-engine)))

;; ---------------------------------------------------------------------------
;; Effects of the UI state machine

(defn- show-prompt! [{:keys [live?]} {:keys [label buffer]}]
  (when (and live? buffer) (term/show-cursor!))
  (term/prompt! label buffer)
  (when (and live? (nil? buffer)) (term/hide-cursor!)))

(defn- watch-effect [env sess {:keys [effect selection] :as e}]
  (case effect
    :start-run (start-run env sess selection)
    :cancel-run (cancel-run env sess)
    :print (do ((:print! env) {:lines (:lines e) :stream :out}) sess)
    :prompt (do (show-prompt! env e) sess)
    :quit (assoc sess :quit? true)))

(defn- drive
  "Feed `inputs` to the UI state machine and carry out its effects. Inputs
  the effects produce (a run that ended at once) are fed in turn."
  [env sess inputs]
  (loop [sess (assoc sess :inbox []) inputs (vec inputs)]
    (if (or (empty? inputs) (:quit? sess))
      sess
      (let [{:keys [state effects]} (watch/step (:ws sess) (first inputs))
            sess (reduce #(watch-effect env %1 %2) (assoc sess :ws state) effects)]
        (recur (assoc sess :inbox []) (into (vec (rest inputs)) (:inbox sess)))))))

;; ---------------------------------------------------------------------------
;; Loop

(defn- watch-loop [{:keys [ctx keys?] :as env} sess]
  (let [^LinkedBlockingQueue queue (:queue ctx)]
    (loop [sess sess last-tick (now-ms) last-poll (now-ms) last-width 0]
      (if (:quit? sess)
        0
        (let [wait (max 0 (- (+ last-tick tick-ms) (now-ms)))
              ev (.poll queue wait TimeUnit/MILLISECONDS)
              at (now-ms)
              tick? (>= (- at last-tick) tick-ms)
              poll? (>= (- at last-poll) watch/poll-ms)
              width? (and tick? (:live? env) (>= (- at last-width) term/width-check-ms))
              engine-inputs (cond-> [] ev (conj ev)
                              tick? (conj (cond-> {:event :tick :at at} width? (assoc :width (term/width)))))
              sess (reduce #(engine-input env %1 %2) (assoc sess :inbox []) engine-inputs)
              [sess changed] (if poll? (scan env sess at) [sess nil])
              inputs (concat (:inbox sess)
                             (when changed [changed])
                             (when keys? (read-keys!))
                             (when tick? [{:event :tick :at at}]))
              sess (drive env sess inputs)]
          (recur sess (if tick? at last-tick) (if poll? at last-poll) (long (if width? at last-width))))))))

(defn watch!
  "Run watch mode until the user quits. Returns the exit code."
  [{:keys [ctx keys? live? config config-file] :as env}]
  (let [sess {:ws (watch/init {:keys? keys? :config-file config-file :color? (:color? (:engine-opts env))})
              :config config
              :infos (read-infos config)
              :snapshot (take-snapshot env config)
              :engine nil
              :command (:command ctx)
              :world {:handles {} :height 0}
              :next-worker-id 1
              :inbox []}
        go (fn [] (watch-loop env (drive env sess [{:event :start}])))
        guarded (fn [] (if keys?
                         (term/with-raw-mode! go pool/kill-descendants!)
                         (term/with-restore! pool/kill-descendants! go)))]
    (if live? (term/with-hidden-cursor! guarded) (guarded))))

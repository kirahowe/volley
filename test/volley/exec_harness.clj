(ns volley.exec-harness
  "Test-only driver: runs real worker processes through the pool and the
  scheduler until the plan is done, and returns every event in arrival order.
  It is the event loop `volley.main` will have, minus rendering."
  (:require
   [volley.schedule :as sched]
   [volley.shell.pool :as pool])
  (:import
   (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn- start-workers [pool-opts handles ids]
  (into handles (map (fn [id] [id (pool/start-worker! pool-opts id)])) ids))

(defn- send-assignments
  "Send `:run` messages for `assigns`; `run-ids` maps ns to the run id."
  [handles run-opts run-ids assigns]
  (doseq [[w ns-sym] assigns]
    (pool/send! (get handles w)
                (merge {:op :run :ns ns-sym :run-id (get run-ids ns-sym)} run-opts))))

(defn- wind-down!
  "Shut every remaining worker down, kill stragglers by handle, and return
  the events still arriving until each one has reported its exit."
  [handles ^LinkedBlockingQueue queue]
  (run! pool/stop! (vals handles))
  (loop [waiting (set (keys handles)) events []]
    (if (empty? waiting)
      events
      (if-let [ev (.poll queue 5 TimeUnit/SECONDS)]
        (recur (cond-> waiting (= :worker/exit (:event ev)) (disj (:worker ev)))
               (conj events ev))
        (do (pool/kill-all! (map handles waiting))
            events)))))

(defn run-plan
  "Run `plan` (`{:parallel [ns ...] :serial [ns ...]}`) in real workers.

  opts: `:workers` (default 1), `:fail-fast?`, `:isolate?`, `:capture-output?`
  (default true), `:select`, `:durations`, `:command`, `:timeout-ms` (no event
  for this long aborts the run, default 60000).

  Returns `{:events [...] :state sched-state :timed-out? bool}`. Events from
  a crashed worker are followed by the synthetic `:ns/error` and `:ns/end`
  from `sched/crash-events`."
  [plan {:keys [timeout-ms capture-output? select command] :or {timeout-ms 60000 capture-output? true} :as opts}]
  (let [queue (pool/new-queue)
        pool-opts (cond-> {:queue queue} command (assoc :command command))
        run-opts {:capture-output? capture-output? :select select}
        run-ids (zipmap (concat (:parallel plan) (:serial plan)) (range 1))]
    (loop [state (sched/init plan opts) handles {} events []]
      (let [[state spawned] (sched/drain-spawns state)
            handles (start-workers pool-opts handles spawned)
            [state assigns] (sched/next-assignments state)
            _ (send-assignments handles run-opts run-ids assigns)
            [state shut] (sched/drain-shutdowns state)
            _ (run! #(some-> (get handles %) pool/stop!) shut)]
        (if (sched/done? state)
          {:events (into events (wind-down! handles queue))
           :state state :timed-out? false}
          (if-let [ev (.poll ^LinkedBlockingQueue queue timeout-ms TimeUnit/MILLISECONDS)]
            (let [crash (sched/crash-events state ev)
                  handles (cond-> handles (= :worker/exit (:event ev)) (dissoc (:worker ev)))]
              (recur (sched/handle-event state ev) handles (-> events (conj ev) (into crash))))
            (do (pool/kill-all! (vals handles))
                {:events events :state state :timed-out? true})))))))

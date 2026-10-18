(ns brisk.schedule
  "Pure scheduler state machine.

  The scheduler never starts processes or sends messages. The shell feeds it
  events and then asks three questions, in this order, after every event:

  * `next-assignments`  which `[worker-id ns]` pairs to send now
  * `drain-spawns`      which new worker ids to start
  * `drain-shutdowns`   which worker ids to tell to shut down

  Worker ids come from the scheduler (1, 2, 3, ...) and are never reused, so
  a replacement worker is always a new id.

  Events it understands (all others are ignored):

  * `{:event :worker/ready :worker id}`
  * `{:event :ns/end :worker id :ns sym}`  (the worker always sends this last,
    also after `:ns/error`)
  * `{:event :ns/error ...}`, `{:event :assertion :type :fail|:error ...}`,
    `{:event :var/end :fail n :error n ...}`  mark the run as failed
  * `{:event :worker/exit :worker id :code n}`  emitted by the pool

  Worker statuses: `:starting`, `:idle`, `:busy`, `:retiring` (shutdown
  requested, process not yet gone). A worker that has exited is removed.")

(def ^:private max-spawn-failures
  "Workers that die before reporting ready, in total, before the scheduler
  stops starting replacements."
  3)

(defn- order-by-duration
  "Longest known namespace first; namespaces without history go first of all,
  since they may be slow. Stable, so equal keys keep plan order."
  [nses durations]
  (sort-by #(- (get durations % Long/MAX_VALUE)) nses))

(defn init
  "State for `plan` `{:parallel [ns ...] :serial [ns ...]}`.

  opts:
  * `:workers`    maximum concurrent workers (default 1)
  * `:fail-fast?` stop assigning after the first failure
  * `:isolate?`   each worker runs one namespace, then is retired
  * `:durations`  `{ns millis}` from earlier runs, for longest-first order"
  [{:keys [parallel serial]} {:keys [workers fail-fast? isolate? durations]}]
  {:queue (vec (order-by-duration parallel durations))
   :serial (vec serial)
   :max-workers (max 1 (or workers 1))
   :fail-fast? (boolean fail-fast?)
   :isolate? (boolean isolate?)
   :workers {}
   :assignments {}
   :assigned []
   :finished #{}
   :crashed {}
   :failed? false
   :spawn-failures 0
   :next-id 1
   :spawn-requests []
   :shutdown-requests []})

;; ---------------------------------------------------------------------------
;; Queries

(defn- count-status [state status]
  (count (filter #{status} (vals (:workers state)))))

(defn- ids-with-status [state status]
  (->> (:workers state) (filter (comp #{status} val)) (map key) sort))

(defn stopped?
  "True when fail-fast is on and a failure has happened: nothing new is
  assigned."
  [{:keys [fail-fast? failed?]}]
  (and fail-fast? failed?))

(defn- spawning-disabled? [{:keys [spawn-failures]}]
  (>= spawn-failures max-spawn-failures))

(defn pending
  "Namespaces not yet assigned, parallel batch first."
  [{:keys [queue serial]}]
  (into (vec queue) serial))

(defn assigned
  "Log of every assignment so far, `[[worker-id ns] ...]` in order."
  [state]
  (:assigned state))

(defn failed?
  "True once any failure, namespace error or worker crash has been seen."
  [state]
  (:failed? state))

(defn done?
  "True when nothing is in flight and nothing more will be assigned: the
  queues are empty, fail-fast stopped the run, or no worker can be started."
  [{:keys [queue serial workers] :as state}]
  (and (zero? (count-status state :busy))
       (or (stopped? state)
           (and (empty? queue) (empty? serial))
           (and (spawning-disabled? state)
                (not-any? #{:idle :starting} (vals workers))))))

(defn crash-events
  "Events to show for `event` when it is a `:worker/exit` of a worker that
  held an assignment: an `:ns/error` describing the crash, then the `:ns/end`
  the dead worker could not send. Empty for any other event. Call before
  `handle-event`."
  [{:keys [assignments]} {:keys [event worker code at]}]
  (if-let [ns-sym (and (= :worker/exit event) (get assignments worker))]
    [{:event :ns/error :ns ns-sym :worker worker :at at :synthetic? true
      :error {:class "brisk.WorkerCrashed"
              :message (str "worker process exited with code " code
                            " while running " ns-sym)
              :data nil :trace [] :exit-code code}}
     {:event :ns/end :ns ns-sym :worker worker :at at :synthetic? true
      :crashed? true}]
    []))

;; ---------------------------------------------------------------------------
;; Events

(defn- set-status [state worker status]
  (if (contains? (:workers state) worker)
    (assoc-in state [:workers worker] status)
    state))

(defn- finish-ns
  "A worker finished `ns-sym`: record it and free or retire the worker."
  [{:keys [isolate?] :as state} worker ns-sym]
  (if (= ns-sym (get-in state [:assignments worker]))
    (let [state (-> state
                    (update :assignments dissoc worker)
                    (update :finished conj ns-sym))]
      (if isolate?
        (-> state
            (set-status worker :retiring)
            (update :shutdown-requests conj worker))
        (set-status state worker :idle)))
    state))

(defn- worker-exit
  [state worker]
  (let [status (get-in state [:workers worker])
        held (get-in state [:assignments worker])]
    (cond-> (-> state
                (update :workers dissoc worker)
                (update :assignments dissoc worker)
                (update :shutdown-requests (fn [rs] (vec (remove #{worker} rs)))))
      (= :starting status) (update :spawn-failures inc)
      held (-> (update :finished conj held)
               (assoc-in [:crashed held] true)
               (assoc :failed? true)))))

(defn- failure-event? [{:keys [event type fail error]}]
  (case event
    :ns/error true
    :assertion (contains? #{:fail :error} type)
    :var/end (pos? (+ (or fail 0) (or error 0)))
    false))

(defn handle-event
  "Fold one event into the state. Total: unknown events and events from
  unknown workers are ignored."
  [state {:keys [event worker ns] :as ev}]
  (let [state (cond-> state (failure-event? ev) (assoc :failed? true))]
    (case event
      :worker/ready (cond-> state
                      (= :starting (get-in state [:workers worker]))
                      (set-status worker :idle))
      :ns/end (finish-ns state worker ns)
      :worker/exit (worker-exit state worker)
      state)))

;; ---------------------------------------------------------------------------
;; Decisions

(defn- non-busy-live
  "Workers that are alive and could take work: starting or idle."
  [state]
  (+ (count-status state :starting) (count-status state :idle)))

(defn- wanted-spare
  "How many non-busy workers should exist right now."
  [{:keys [queue serial max-workers] :as state}]
  (let [busy (count-status state :busy)]
    (cond
      (or (stopped? state) (spawning-disabled? state)) 0
      (seq queue) (min (max 0 (- max-workers busy)) (count queue))
      (seq serial) 1
      :else 0)))

(defn- assign [state worker ns-sym]
  (-> state
      (assoc-in [:assignments worker] ns-sym)
      (assoc-in [:workers worker] :busy)
      (update :assigned conj [worker ns-sym])))

(defn- assign-parallel [state idle]
  (reduce (fn [st worker]
            (if-let [ns-sym (first (:queue st))]
              (-> st (update :queue subvec 1) (assign worker ns-sym))
              (reduced st)))
          state idle))

(defn- assign-serial
  "A serial namespace runs only when nothing else is in flight."
  [state idle]
  (if (and (empty? (:queue state))
           (seq (:serial state))
           (seq idle)
           (zero? (count-status state :busy)))
    (let [ns-sym (first (:serial state))]
      (-> state (update :serial subvec 1) (assign (first idle) ns-sym)))
    state))

(defn next-assignments
  "`[state' [[worker-id ns] ...]]`: work to send now. Assigns to idle workers
  in id order; the serial batch only once the parallel batch is finished and
  every worker is idle, and then one namespace at a time."
  [state]
  (if (stopped? state)
    [state []]
    (let [idle (ids-with-status state :idle)
          n (count (:assigned state))
          state' (if (seq (:queue state))
                   (assign-parallel state idle)
                   (assign-serial state idle))]
      [state' (subvec (:assigned state') n)])))

(defn drain-spawns
  "`[state' [worker-id ...]]`: new workers to start so that enough non-busy
  workers exist for the remaining work, never exceeding `:workers` alive
  and busy together. Each id is fresh."
  [state]
  (let [need (- (wanted-spare state) (non-busy-live state))
        room (- (:max-workers state)
                (+ (count-status state :busy) (non-busy-live state)))
        n (max 0 (min need room))
        ids (vec (range (:next-id state) (+ (:next-id state) n)))]
    [(-> state
         (update :next-id + n)
         (update :workers into (map (fn [id] [id :starting]) ids)))
     ids]))

(defn drain-shutdowns
  "`[state' [worker-id ...]]`: workers to tell to shut down, namely those
  retired by isolate mode plus idle workers beyond what the remaining work
  needs (highest ids first). Retiring workers stay known until their exit."
  [state]
  (let [surplus (- (non-busy-live state) (wanted-spare state))
        trim (->> (ids-with-status state :idle) reverse (take (max 0 surplus)))
        ids (vec (concat (:shutdown-requests state) trim))]
    [(reduce #(set-status %1 %2 :retiring)
             (assoc state :shutdown-requests [])
             ids)
     ids]))

(defn live-workers
  "Ids of workers that have not exited, in any status."
  [state]
  (vec (sort (keys (:workers state)))))

(defn summary
  "Counts for reporting: `{:finished n :pending n :crashed n :failed? bool}`."
  [state]
  {:finished (count (:finished state))
   :pending (count (pending state))
   :crashed (count (:crashed state))
   :failed? (:failed? state)})

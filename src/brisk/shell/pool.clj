(ns brisk.shell.pool
  "Worker processes. Starts them, feeds them assignments, and pumps what they
  print into ONE `LinkedBlockingQueue` the caller owns.

  Everything put on the queue is a flat event map (see `brisk.protocol/->event`):
  worker events with `:worker` and `:at` added, stray output as
  `{:event :output :text ... :stream :stdout|:stderr}`, and a final
  `{:event :worker/exit :worker id :code n}` once the process has exited
  and both streams hit EOF, or 500ms after the exit when something else
  (a process the test left running) still holds them open.
  The scheduler decides when to start and stop workers; this namespace has
  no policy of its own."
  (:require
   [babashka.process :as p]
   [brisk.protocol :as protocol]
   [brisk.shell.runtime :as runtime]
   [clojure.java.io :as io])
  (:import
   (java.util.concurrent LinkedBlockingQueue)))

(set! *warn-on-reflection* true)

(defn bb-command
  "Command line for a babashka worker with classpath `classpath` (default:
  the parent's)."
  ([] (bb-command (runtime/parent-classpath)))
  ([classpath]
   ["bb" "-cp" (or classpath "") "-m" "brisk.shell.worker"]))

(defn- pump!
  "Read `stream` line by line, putting each message `decode` makes of a
  line as an event on `queue`."
  [^LinkedBlockingQueue queue worker stream decode]
  (with-open [rdr (io/reader (if (= stream :stdout) (:out worker) (:err worker))
                             :encoding "UTF-8")]
    (doseq [line (line-seq rdr)
            message (decode line)]
      (.put queue (protocol/->event message
                                    {:worker (:id worker)
                                     :at (System/currentTimeMillis)
                                     :stream stream})))))

(defn- new-nonce
  "A fresh random token for one worker's protocol prefix."
  []
  (subs (str (java.util.UUID/randomUUID)) 0 8))

(def ^:private reader-grace-ms
  "How long after a worker exits its output may still drain before the
  pool reports the exit."
  500)

(defn- daemon-thread ^Thread [^Runnable f]
  (doto (Thread. f) (.setDaemon true) (.start)))

(defn start-worker!
  "Start worker `id`. opts:
  * `:queue`   the shared `LinkedBlockingQueue` (required)
  * `:command` argv vector (default `(bb-command)`)
  * `:dir`, `:env`  passed to the process
  The worker gets a fresh nonce as its last argument and prefixes its
  protocol lines with it (`brisk.protocol/prefix-for`), so a child process
  of a test that writes to the inherited stdout can never forge an event.
  Returns a handle `{:id :proc :in}` for `send!`, `stop!` and `kill!`."
  [{:keys [queue command dir env]} id]
  (let [nonce (new-nonce)
        prefix (protocol/prefix-for nonce)
        proc (p/process (conj (vec (or command (bb-command))) nonce)
                        (cond-> {:in :pipe :out :pipe :err :pipe}
                          dir (assoc :dir dir)
                          env (assoc :extra-env env)))
        worker {:id id :out (:out proc) :err (:err proc)}
        readers [(daemon-thread #(pump! queue worker :stdout
                                        (partial protocol/decode-line prefix)))
                 (daemon-thread #(pump! queue worker :stderr
                                        (fn [line] [{:kind :output :text line}])))]]
    (daemon-thread
     (fn []
       (let [code (:exit @proc)
             deadline (+ (System/currentTimeMillis) reader-grace-ms)]
         ;; A process the test started in the background may hold the
         ;; worker's pipes open long after the worker is gone, so the
         ;; readers get a short grace period, not an unbounded join.
         (run! #(.join ^Thread % (long (max 1 (- deadline (System/currentTimeMillis))))) readers)
         (.put ^LinkedBlockingQueue queue
               {:event :worker/exit :worker id :code code
                :at (System/currentTimeMillis)}))))
    {:id id
     :proc proc
     :in (io/writer (:in proc) :encoding "UTF-8")}))

(defn send!
  "Send a parent-to-worker message map. Returns true, or false when the
  worker's stdin is already closed (it has died or been shut down)."
  [{:keys [in]} message]
  (try
    (locking in
      (.write ^java.io.Writer in (str (protocol/encode-command message) "\n"))
      (.flush ^java.io.Writer in))
    true
    (catch java.io.IOException _ false)))

(defn stop!
  "Ask a worker to shut down cleanly and close its stdin."
  [{:keys [in] :as worker}]
  (send! worker {:op :shutdown})
  (try (.close ^java.io.Writer in) (catch java.io.IOException _ nil))
  nil)

(defn kill!
  "Forcibly kill a worker by its process handle."
  [{:keys [proc]}]
  (p/destroy-tree proc)
  nil)

(defn kill-descendants!
  "Forcibly kill every process this one started, directly or not: the
  workers and whatever they started. Used when the run ends and in a
  shutdown hook, where no worker handle is at hand."
  []
  (doseq [^java.lang.ProcessHandle h (iterator-seq (.iterator (.descendants (java.lang.ProcessHandle/current))))]
    (.destroyForcibly h))
  nil)

(defn kill-all!
  "Kill every worker handle in `workers` (a seq of handles)."
  [workers]
  (run! kill! workers))

(defn new-queue
  "The one event queue for a run."
  ^LinkedBlockingQueue []
  (LinkedBlockingQueue.))

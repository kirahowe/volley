(ns brisk.shell.pool
  "Worker processes. Starts them, feeds them assignments, and pumps what they
  print into ONE `LinkedBlockingQueue` the caller owns.

  Everything put on the queue is a flat event map (see `brisk.protocol/->event`):
  worker events with `:worker` and `:at` added, stray output as
  `{:event :output :text ... :stream :stdout|:stderr}`, and a final
  `{:event :worker/exit :worker id :code n}` after both streams hit EOF.
  The scheduler decides when to start and stop workers; this namespace has
  no policy of its own."
  (:require
   [babashka.classpath :as cp]
   [babashka.process :as p]
   [brisk.protocol :as protocol]
   [clojure.java.io :as io])
  (:import
   (java.util.concurrent LinkedBlockingQueue)))

(defn bb-command
  "Command line for a babashka worker with classpath `classpath` (default:
  the parent's)."
  ([] (bb-command (cp/get-classpath)))
  ([classpath]
   ["bb" "-cp" (or classpath "") "-m" "brisk.shell.worker"]))

(defn jvm-command
  "Command line for a JVM Clojure worker. Not wired into the CLI yet; it
  exists so the `:runtime :jvm` mode only has to pass it as `:command`."
  [classpath]
  ["java" "-cp" classpath "clojure.main" "-m" "brisk.shell.worker"])

(defn- pump!
  "Read `stream` line by line, putting each as an event on `queue`."
  [^LinkedBlockingQueue queue worker stream decode]
  (with-open [rdr (io/reader (if (= stream :stdout) (:out worker) (:err worker))
                             :encoding "UTF-8")]
    (doseq [line (line-seq rdr)]
      (.put queue (protocol/->event (decode line)
                                    {:worker (:id worker)
                                     :at (System/currentTimeMillis)
                                     :stream stream})))))

(defn- daemon-thread ^Thread [^Runnable f]
  (doto (Thread. f) (.setDaemon true) (.start)))

(defn start-worker!
  "Start worker `id`. opts:
  * `:queue`   the shared `LinkedBlockingQueue` (required)
  * `:command` argv vector (default `(bb-command)`)
  * `:dir`, `:env`  passed to the process
  Returns a handle `{:id :proc :in}` for `send!`, `stop!` and `kill!`."
  [{:keys [queue command dir env]} id]
  (let [proc (p/process (or command (bb-command))
                        (cond-> {:in :pipe :out :pipe :err :pipe}
                          dir (assoc :dir dir)
                          env (assoc :extra-env env)))
        worker {:id id :out (:out proc) :err (:err proc)}
        readers [(daemon-thread #(pump! queue worker :stdout protocol/decode-line))
                 (daemon-thread #(pump! queue worker :stderr
                                        (fn [line] {:kind :output :text line})))]]
    (daemon-thread
     (fn []
       (let [code (:exit @proc)]
         (run! #(.join ^Thread %) readers)
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

(defn alive?
  "True while the worker's process is running."
  [{:keys [proc]}]
  (.isAlive ^Process (:proc proc)))

(defn kill-all!
  "Kill every worker handle in `workers` (a seq of handles)."
  [workers]
  (run! kill! workers))

(defn new-queue
  "The one event queue for a run."
  ^LinkedBlockingQueue []
  (LinkedBlockingQueue.))

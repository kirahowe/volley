(ns volley.shell.in-process
  "A worker on a thread of the parent JVM instead of a process of its own,
  for runs that `volley.runtime/worker-plan` judges too short to pay for a
  second JVM. It runs the worker's own loop (`volley.shell.worker/serve!`)
  over in-memory streams: command lines come from the handle's `:in`
  writer, and protocol lines and stray output become events on the
  parent's queue exactly as `volley.shell.pool` makes them from a worker
  process's stdout and stderr.

  While it runs, `System/out` and `System/err` point at its stray output and
  `System/in` at an empty stream, as a worker process sets them up. The
  parent prints through the root `*out*` and `*err*`, which hold the
  original streams, so its output is unaffected. The worker's thread
  binds `*out*`, `*err*` and `t/*test-out*` to its stray output instead,
  so `println` on a thread without those bindings goes to the terminal,
  as does whatever the thread writes to `System/out` after `kill!` (see
  In process in doc/design.md).

  JVM only: `volley.main` loads it with `requiring-resolve` when a run is
  in process."
  (:require
   [volley.protocol :as protocol]
   [volley.shell.pool :as pool]
   [volley.shell.worker :as worker]
   [clojure.test :as t])
  (:import
   (java.io BufferedReader ByteArrayOutputStream OutputStream OutputStreamWriter PrintStream StringReader)
   (java.util.concurrent LinkedBlockingQueue)))

(set! *warn-on-reflection* true)

(defn- last-newline
  "Index of the last `\\n` in the first `n` bytes of `bs`, or -1."
  [^bytes bs n]
  (loop [i (dec n)]
    (cond
      (neg? i) -1
      (== 10 (aget bs i)) i
      :else (recur (dec i)))))

(defn- line-stream
  "An OutputStream that calls `(on-line s)` for each complete line written
  to it (UTF-8, split as `line-seq` splits), and on close passes on a
  partial last line and calls `(on-close)`, once."
  ^OutputStream [on-line on-close]
  (let [buf (ByteArrayOutputStream.)
        closed? (volatile! false)
        drain! (fn [final?]
                 (let [bs (.toByteArray buf)
                       n (alength bs)
                       upto (if final? n (inc (long (last-newline bs n))))]
                   (when (pos? upto)
                     (.reset buf)
                     (.write buf bs (int upto) (int (- n upto)))
                     (run! on-line (line-seq (BufferedReader. (StringReader. (String. bs 0 (int upto) "UTF-8"))))))))]
    (proxy [OutputStream] []
      (write
        ([b]
         (locking buf
           (if (bytes? b)
             (.write buf ^bytes b 0 (alength ^bytes b))
             (.write buf (int b)))
           (drain! false)))
        ([b off len]
         (locking buf
           (.write buf ^bytes b (int off) (int len))
           (drain! false))))
      (close []
        (locking buf
          (when-not @closed?
            (vreset! closed? true)
            (drain! true)
            (on-close)))))))

(defn start-worker!
  "Start worker `id` on a daemon thread of this JVM, sending its events to
  `:queue` as `volley.shell.pool/start-worker!` does, `:worker/exit` last.
  Returns a handle for `volley.shell.pool/send!`, `stop!` and `kill!`.
  A thread cannot be killed: `kill!` interrupts it and reports the exit at
  once, and whatever it still sends goes to a worker the run has dropped."
  [{:keys [^LinkedBlockingQueue queue]} id]
  (let [prefix (protocol/prefix-for (pool/new-nonce))
        commands (LinkedBlockingQueue.)
        eof (Object.)
        exited? (atom false)
        saved [System/out System/err System/in]
        exit! (fn [code]
                (when (compare-and-set! exited? false true)
                  (let [[out err in] saved]
                    (System/setOut out)
                    (System/setErr err)
                    (System/setIn in))
                  (.put queue {:event :worker/exit :worker id :code code
                               :at (System/currentTimeMillis)})))
        proto (line-stream #(pool/put-line! queue id :stdout (partial protocol/decode-line prefix) %)
                           (fn []))
        stray-stream (PrintStream. (line-stream #(pool/put-line! queue id :stderr
                                                                  (fn [line] [{:kind :output :text line}]) %)
                                                (fn []))
                                   true "UTF-8")
        stray (OutputStreamWriter. stray-stream "UTF-8")
        emit! (worker/make-emit (OutputStreamWriter. proto "UTF-8") prefix)
        in (OutputStreamWriter. (line-stream #(.put commands %) #(.put commands eof)) "UTF-8")
        read-line #(let [x (.take commands)] (when-not (identical? x eof) x))
        ;; the bindings of the thread that starts it (clojure.main's
        ;; *ns* and friends), as a worker process's -main has them
        serve (bound-fn []
                (binding [*out* stray *err* stray t/*test-out* stray]
                  (let [code (try (worker/serve! emit! read-line stray)
                                  (catch Throwable e
                                    (println "volley: in-process worker failed:" (str e))
                                    1))]
                    (.flush stray)
                    (.close stray-stream)
                    (.close proto)
                    (exit! code))))
        thread (doto (Thread. ^Runnable serve (str "volley-worker-" id))
                 (.setDaemon true))]
    (System/setIn (java.io.ByteArrayInputStream. (byte-array 0)))
    (System/setOut stray-stream)
    (System/setErr stray-stream)
    (.start thread)
    {:id id
     :in in
     :kill! (fn [] (.put commands eof) (.interrupt thread) (exit! 1))}))

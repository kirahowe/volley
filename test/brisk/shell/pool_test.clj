(ns brisk.shell.pool-test
  "Real worker processes through the pool, the scheduler and the harness."
  (:require
   [brisk.exec-harness :as harness]
   [brisk.schedule :as sched]
   [brisk.shell.pool :as pool]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.util.concurrent TimeUnit)))

(defn- drain
  "Take events from `queue` until `pred` matches one or `ms` pass."
  [queue pred ms]
  (loop [acc []]
    (if-let [ev (.poll queue ms TimeUnit/MILLISECONDS)]
      (if (pred ev) (conj acc ev) (recur (conj acc ev)))
      acc)))

(deftest pump-decodes-both-streams-and-reports-exit
  (let [queue (pool/new-queue)
        w (pool/start-worker!
           {:queue queue
            :command ["sh" "-c" "echo hi; echo '#brisk/event {:event :worker/ready}'; echo oops >&2; exit 4"]}
           9)
        evs (drain queue #(= :worker/exit (:event %)) 5000)
        by-event (group-by :event evs)]
    (is (= ["hi"] (map :text (filter #(= :stdout (:stream %)) (:output by-event)))))
    (is (= ["oops"] (map :text (filter #(= :stderr (:stream %)) (:output by-event)))))
    (is (= 9 (:worker (first (:worker/ready by-event)))))
    (is (every? :at evs))
    (is (= [4] (map :code (:worker/exit by-event))) "exit comes last, with the code")
    (is (= :worker/exit (:event (peek evs))))
    (is (false? (pool/send! w {:op :shutdown})) "writing to a dead worker reports false")))

(defn- alive? [w] (.isAlive ^Process (:proc (:proc w))))

(deftest kill-uses-the-process-handle
  (let [queue (pool/new-queue)
        w (pool/start-worker! {:queue queue :command ["sleep" "30"]} 1)]
    (is (alive? w))
    (pool/kill! w)
    (let [exit (peek (drain queue #(= :worker/exit (:event %)) 5000))]
      (is (= :worker/exit (:event exit)))
      (is (not= 0 (:code exit)))
      (is (not (alive? w))))))

(defn- events-of [r kind] (filter #(= kind (:event %)) (:events r)))

(defn- ns-order [r]
  (->> (:events r)
       (filter #(#{:ns/start :ns/end} (:event %)))
       (map (juxt :event :ns))))

(def all-fixtures
  '{:parallel [exec.pass-test exec.fail-test exec.error-test exec.load-error-test
               exec.fixtures-test exec.contexts-test exec.output-test exec.exit-test
               exec.slow-test]
    :serial [exec.sync-test]})

(deftest full-run-with-real-workers
  (let [r (harness/run-plan all-fixtures {:workers 4})
        ends (into {} (map (juxt :ns identity)) (events-of r :ns/end))]
    (is (not (:timed-out? r)))
    (is (sched/done? (:state r)))
    (testing "every namespace reports start and end exactly once"
      (is (= 10 (count (events-of r :ns/start))))
      (is (= (set (concat (:parallel all-fixtures) (:serial all-fixtures)))
             (set (keys ends))))
      (is (= 10 (count (events-of r :ns/end)))))
    (testing "the serial namespace starts after every other one has ended"
      (let [order (ns-order r)
            serial-start (.indexOf ^java.util.List order [:ns/start 'exec.sync-test])
            last-other-end (apply max (keep-indexed
                                       (fn [i [kind n]]
                                         (when (and (= kind :ns/end) (not= n 'exec.sync-test)) i))
                                       order))]
        (is (< last-other-end serial-start))))
    (testing "the load error and the crash are namespace errors"
      (is (= #{'exec.load-error-test 'exec.exit-test}
             (set (map :ns (events-of r :ns/error)))))
      (is (true? (:crashed? (ends 'exec.exit-test)))))
    (testing "output captured per var, System/out included"
      (let [out (fn [v] (:output (first (filter #(= v (:var %)) (events-of r :var/end)))))]
        (is (= "hello from println\nhello on err\n" (out 'prints-via-println)))
        (is (= "direct system out\npartial " (out 'prints-via-system-out))))
      (is (not-any? #(= "direct system out" (:text %)) (events-of r :output))))
    (testing "no protocol line was damaged"
      (is (not-any? :protocol-error (events-of r :output))))
    (testing "workers exit cleanly except the one the test killed"
      (is (= #{0 3} (set (map :code (events-of r :worker/exit))))))))

(deftest fail-fast-stops-handing-out-work
  (let [r (harness/run-plan '{:parallel [exec.fail-test exec.pass-test exec.fixtures-test]}
                            {:workers 1 :fail-fast? true})]
    (is (= '[exec.fail-test] (map :ns (events-of r :ns/end))))
    (is (= '#{exec.pass-test exec.fixtures-test} (set (sched/pending (:state r)))))))

(deftest isolate-uses-a-fresh-process-per-namespace
  (let [r (harness/run-plan '{:parallel [exec.pass-test exec.fixtures-test exec.skip-test]}
                            {:workers 2 :isolate? true})
        workers (map :worker (events-of r :ns/start))]
    (is (= 3 (count workers)))
    (is (= 3 (count (set workers))))
    (is (= 3 (count (events-of r :ns/end))))))

(deftest jvm-worker-runs-the-same-protocol
  (let [r (harness/run-plan '{:parallel [exec.pass-test exec.fail-test exec.contexts-test]}
                            {:workers 1
                             :command ["clojure" "-Sdeps" "{:paths [\"src\" \"dev-resources\"]}"
                                       "-M" "-m" "brisk.shell.worker"]
                             :timeout-ms 120000})]
    (is (not (:timed-out? r)))
    (is (= 3 (count (events-of r :ns/end))))
    (is (= 3 (count (filter #(= :fail (:type %)) (events-of r :assertion)))))
    (is (= 1 (count (filter #(= :fail (:type %))
                            (filter #(= 'exec.fail-test (:ns %)) (events-of r :assertion)))))
        "the nested-data mismatch")))

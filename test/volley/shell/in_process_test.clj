(ns volley.shell.in-process-test
  "A worker on a thread of this process, against the fixture namespaces in
  dev-resources/exec: it sends what a worker process sends, its exit last,
  and leaves the process's streams as it found them."
  (:require
   [volley.shell.in-process :as in-process]
   [volley.shell.pool :as pool]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(defn- until
  "Events from `queue` up to and including the first that `done?` holds
  for, or all of them if none arrives within 20s."
  [^LinkedBlockingQueue queue done?]
  (loop [acc []]
    (let [e (.poll queue 20 TimeUnit/SECONDS)]
      (cond
        (nil? e) acc
        (done? e) (conj acc e)
        :else (recur (conj acc e))))))

(defn- until-exit
  "Events from `queue` up to and including the first `:worker/exit`."
  [queue]
  (until queue #(= :worker/exit (:event %))))

(defn- run-in-process
  "Run `msgs` in an in-process worker with id 5, then stop it once they
  have ended (a `:shutdown` stops a running namespace). Returns its
  events."
  [msgs]
  (let [queue (pool/new-queue)
        handle (in-process/start-worker! {:queue queue} 5)
        ends (volatile! 0)
        _ (run! #(pool/send! handle %) msgs)
        ran (until queue #(and (= :ns/end (:event %)) (= (count msgs) (vswap! ends inc))))]
    (pool/stop! handle)
    (into ran (until-exit queue))))

(deftest serves-runs-like-a-worker-process
  (let [streams [System/out System/err System/in]
        events (run-in-process [{:op :run :ns 'exec.pass-test :run-id 1 :capture-output? true}
                                {:op :run :ns 'exec.output-test :run-id 2 :capture-output? false}])]
    (is (= :worker/ready (:event (first events))))
    (is (= {:event :worker/exit :worker 5 :code 0} (dissoc (last events) :at)))
    (is (every? #(= 5 (:worker %)) events))
    (is (= [['exec.pass-test 1] ['exec.output-test 2]]
           (map (juxt :ns :run-id) (filter #(= :ns/end (:event %)) events))))
    (is (= {'adds 0 'strings 0 'prints-via-println 0 'prints-via-system-out 1}
           (into {} (map (juxt :var :fail)) (filter #(= :var/end (:event %)) events))))
    (testing "uncaptured output, System/out included, arrives as output events"
      (is (= #{"hello from println" "hello on err" "direct system out" "partial "}
             (set (keep #(when (= :output (:event %)) (:text %)) events)))))
    (testing "the process's own streams are back"
      (is (= streams [System/out System/err System/in])))))

(deftest kill-reports-the-exit-at-once
  (let [queue (pool/new-queue)
        handle (in-process/start-worker! {:queue queue} 6)]
    (pool/send! handle {:op :run :ns 'exec.slow-test :run-id 1 :capture-output? true})
    (pool/kill! handle)
    (let [events (until-exit queue)]
      (is (= {:event :worker/exit :worker 6 :code 1} (dissoc (last events) :at))))))

(deftest a-var-ending-after-kill-leaves-the-streams-alone
  ;; the killed thread still ends its var, and must not put its own stray
  ;; stream back as System/out and System/err
  (let [streams [System/out System/err]
        queue (pool/new-queue)
        handle (in-process/start-worker! {:queue queue} 7)]
    (pool/send! handle {:op :run :ns 'exec.slow-test :run-id 1 :capture-output? true})
    (until queue #(= :var/start (:event %)))
    (pool/kill! handle)
    (until-exit queue)
    (is (= :var/end (:event (last (until queue #(= :var/end (:event %)))))))
    (is (= streams [System/out System/err]))))

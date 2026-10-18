(ns volley.schedule-test
  (:require
   [volley.gen.exec :as g]
   [volley.schedule :as sched]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

(defn- sim-prop
  "Property over `simulate` results for arbitrary plans, options, scripts
  and failure rates."
  [check]
  (prop/for-all [plan g/gen-plan
                 opts g/gen-sched-opts
                 script g/gen-script
                 chaos (gen/choose 0 3)]
    (check plan opts (g/simulate plan opts script chaos))))

(defspec no-protocol-violations 300
  (sim-prop (fn [_ _ {:keys [violations]}] (empty? violations))))

(defspec always-reaches-done 300
  (sim-prop (fn [_ _ {:keys [done?]}] done?)))

(defspec each-namespace-assigned-at-most-once 300
  (sim-prop (fn [_ _ {:keys [state]}]
              (let [nses (map second (:assigned state))]
                (= (count nses) (count (set nses)))))))

(defspec every-namespace-assigned-unless-fail-fast-stopped 300
  (sim-prop (fn [plan _ {:keys [state]}]
              (let [wanted (set (concat (:parallel plan) (:serial plan)))
                    got (set (map second (:assigned state)))]
                (if (sched/stopped? state)
                  (and (every? wanted got)
                       (= wanted (into got (sched/pending state))))
                  (= wanted got))))))

(defspec serial-namespaces-come-after-all-parallel-ones 300
  (sim-prop (fn [plan _ {:keys [state]}]
              (let [order (map second (:assigned state))
                    serial (set (:serial plan))
                    [par ser] (split-with (complement serial) order)]
                (and (every? serial ser)
                     (or (empty? ser) (= (set par) (set (:parallel plan)))))))))

(defspec isolate-never-reuses-a-worker 300
  (sim-prop (fn [_ opts {:keys [state spawned]}]
              (let [workers (map first (:assigned state))]
                (and (= spawned (distinct spawned))
                     (or (not (:isolate? opts))
                         (= workers (distinct workers))))))))

(defspec worker-ids-are-never-reused 300
  (sim-prop (fn [_ _ {:keys [spawned]}] (= spawned (distinct spawned)))))

(defspec fail-fast-stops-handing-out-work 300
  (sim-prop (fn [plan opts {:keys [state]}]
              (let [total (+ (count (:parallel plan)) (count (:serial plan)))]
                (or (not (:fail-fast? opts))
                    (not (:failed? state))
                    (<= (count (:assigned state)) total))))))

(defspec longest-known-namespace-is-assigned-first 200
  (prop/for-all [opts g/gen-sched-opts
                 plan g/gen-plan]
    (let [order (:queue (sched/init plan opts))
          known (filter #(contains? (:durations opts) %) order)
          ds (map (:durations opts) known)]
      (and (= (set order) (set (:parallel plan)))
           (= ds (reverse (sort ds)))))))

(deftest init-orders-longest-first
  (let [s (sched/init {:parallel '[a b c d]} {:workers 2 :durations '{a 10 b 500 c 100}})]
    (is (= '[d b c a] (:queue s)) "unknown first, then slowest to fastest")))

(defn- ev [event worker & {:as more}]
  (merge {:event event :worker worker} more))

(defn- step
  "spawn + assign + shutdown, returning [state {:spawn :assign :shutdown}]"
  [state]
  (let [[s1 spawn] (sched/drain-spawns state)
        [s2 assign] (sched/next-assignments s1)
        [s3 shutdown] (sched/drain-shutdowns s2)]
    [s3 {:spawn spawn :assign assign :shutdown shutdown}]))

(deftest basic-flow
  (let [[s a] (step (sched/init {:parallel '[a b] :serial '[z]} {:workers 2}))]
    (is (= [1 2] (:spawn a)))
    (is (empty? (:assign a)) "nothing is assigned before workers are ready")
    (let [[s a] (step (reduce sched/handle-event s [(ev :worker/ready 1) (ev :worker/ready 2)]))]
      (is (= '[[1 a] [2 b]] (:assign a)))
      (let [[s a] (step (sched/handle-event s (ev :ns/end 1 :ns 'a)))]
        (is (empty? (:assign a)) "serial waits for the parallel batch")
        (is (empty? (:shutdown a)) "one idle worker is kept for the serial batch")
        (let [[s a] (step (sched/handle-event s (ev :ns/end 2 :ns 'b)))]
          (is (= '[[1 z]] (:assign a)) "the lowest idle id takes the serial ns")
          (is (= [2] (:shutdown a)) "the other worker is not needed")
          (is (not (sched/done? s)))
          (let [[s a] (step (sched/handle-event s (ev :ns/end 1 :ns 'z)))]
            (is (sched/done? s))
            (is (= [1] (:shutdown a)))))))))

(deftest worker-crash-reports-and-replaces
  (let [[s _] (step (sched/init {:parallel '[a b c]} {:workers 1}))
        [s _] (step (sched/handle-event s (ev :worker/ready 1)))
        exit (ev :worker/exit 1 :code 3 :at 7)
        crash (sched/crash-events s exit)]
    (is (= '[a] (map second (:assigned s))))
    (is (= [:ns/error :ns/end] (map :event crash)))
    (is (= ['a 'a] (map :ns crash)))
    (is (= 3 (get-in (first crash) [:error :exit-code])))
    (let [s (sched/handle-event s exit)
          [s a] (step s)]
      (is (= [2] (:spawn a)) "a replacement is requested, with a fresh id")
      (is (= {:finished 1 :pending 2 :crashed 1 :failed? true}
             {:finished (count (:finished s)) :pending (count (sched/pending s))
              :crashed (count (:crashed s)) :failed? (:failed? s)}))
      (is (not (sched/done? s)))
      (is (empty? (sched/crash-events s (ev :worker/exit 2 :code 0)))))))

(deftest crash-of-an-idle-worker-yields-no-events
  (let [[s _] (step (sched/init {:parallel '[a]} {:workers 1}))
        s (sched/handle-event s (ev :worker/ready 1))]
    (is (empty? (sched/crash-events s (ev :worker/exit 1 :code 0))))))

(deftest fail-fast-ends-the-run-after-in-flight-work
  (let [[s _] (step (sched/init {:parallel '[a b c d]} {:workers 2 :fail-fast? true}))
        [s a] (step (reduce sched/handle-event s [(ev :worker/ready 1) (ev :worker/ready 2)]))]
    (is (= 2 (count (:assign a))))
    (let [s (sched/handle-event s (ev :assertion 1 :ns 'a :type :fail))
          [s a] (step (sched/handle-event s (ev :ns/end 1 :ns 'a)))]
      (is (empty? (:assign a)))
      (is (not (sched/done? s)) "worker 2 is still running")
      (let [[s a] (step (sched/handle-event s (ev :ns/end 2 :ns 'b)))]
        (is (empty? (:assign a)))
        (is (sched/done? s))
        (is (= '[c d] (sched/pending s)))))))

(deftest isolate-retires-and-replaces
  (let [[s _] (step (sched/init {:parallel '[a b]} {:workers 1 :isolate? true}))
        [s _] (step (sched/handle-event s (ev :worker/ready 1)))
        [s a] (step (sched/handle-event s (ev :ns/end 1 :ns 'a)))]
    (is (= [1] (:shutdown a)))
    (is (= [2] (:spawn a)))
    (let [[s a] (step (sched/handle-event s (ev :worker/exit 1 :code 0)))
          [_ a2] (step (sched/handle-event s (ev :worker/ready 2)))]
      (is (empty? (:assign a)))
      (is (= '[[2 b]] (:assign a2))))))

(deftest workers-that-never-start-end-the-run
  (let [[s _] (step (sched/init {:parallel '[a b]} {:workers 1}))
        s (loop [s s n 0]
            (if (or (>= n 5) (sched/done? s))
              s
              (let [id (first (sched/live-workers s))
                    s (sched/handle-event s (ev :worker/exit id :code 1))
                    [s _] (step s)]
                (recur s (inc n)))))]
    (is (sched/done? s))
    (is (= '[a b] (sched/pending s)))))

(deftest empty-plan-is-done
  (is (sched/done? (sched/init {:parallel [] :serial []} {:workers 4}))))

(deftest unknown-events-are-ignored
  (let [s (sched/init {:parallel '[a]} {:workers 1})]
    (testing "unknown worker and unknown event"
      (is (= s (sched/handle-event s (ev :ns/end 99 :ns 'a))))
      (is (= s (sched/handle-event s {:event :output :text "x"}))))))

(deftest interrupt-gives-up-other-workers-namespaces
  (let [s0 (sched/init {:parallel '[a b c d] :serial []} {:workers 3 :fail-fast? true})
        [s1 ids] (sched/drain-spawns s0)
        s2 (reduce #(sched/handle-event %1 {:event :worker/ready :worker %2}) s1 ids)
        [s3 assigned] (sched/next-assignments s2)
        s4 (sched/handle-event s3 {:event :assertion :type :fail :worker 1 :ns 'a})
        [s5 taken] (sched/interrupt s4 1)]
    (is (= '[[1 a] [2 b] [3 c]] assigned))
    (is (sched/stopped? s4))
    (is (= '[[2 b] [3 c]] taken))
    (is (= '{1 a} (:assignments s5)))
    (testing "a killed worker's exit is not a crash"
      (is (empty? (sched/crash-events s5 {:event :worker/exit :worker 2 :code 137}))))
    (testing "the run is done once the failing worker ends its namespace"
      (is (not (sched/done? s5)))
      (is (sched/done? (sched/handle-event s5 {:event :ns/end :worker 1 :ns 'a}))))))

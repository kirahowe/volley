(ns brisk.run-state-test
  (:require
   [brisk.gen.report :as gr]
   [brisk.run-state :as rs]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.properties :as prop]))

(def ^:private runs 60)

(defn- all-events [r] (gr/events r 0))

(defn- count-events [events pred] (count (filter pred events)))

(defspec interleaving-independence runs
  (prop/for-all [r gr/run]
    (= (gr/fold (:plan r) (gr/events r 0))
       (gr/fold (:plan r) (gr/events r 1)))))

(defspec summary-counts-are-consistent runs
  (prop/for-all [r gr/run]
    (let [events (all-events r)
          s (rs/summary (gr/fold (:plan r) events))
          started (set (keep (fn [e] (when (#{:var/start :var/skipped} (:event e))
                                       [(:ns e) (:var e)]))
                             events))
          {:keys [vars assertions]} s]
      (and (= (:total vars) (count started))
           (= (:total vars) (+ (:passed vars) (:failed vars) (:errored vars)
                               (:skipped vars) (:running vars)))
           (= (:total assertions) (+ (:passed assertions) (:failed assertions)
                                     (:errored assertions)))
           (= (:failed assertions)
              (count-events events #(and (= :assertion (:event %)) (= :fail (:type %)))))
           (= (:errored assertions)
              (count-events events #(and (= :assertion (:event %)) (= :error (:type %)))))
           (= (:passed assertions)
              (reduce + (keep #(when (= :var/end (:event %)) (:pass %)) events)))))))

(defspec exit-code-is-zero-iff-nothing-failed runs
  (prop/for-all [r gr/run]
    (let [events (all-events r)
          state (gr/fold (:plan r) events)
          bad? (some #(or (= :assertion (:event %))
                          (= :ns/error (:event %))
                          (and (= :worker/exit (:event %)) (pos? (:exit-code %))))
                     events)]
      (and (= (boolean bad?) (pos? (rs/exit-code state)))
           (= (zero? (rs/exit-code state)) (empty? (rs/failures state)))
           (<= (rs/exit-code state) 255)))))

(defspec failures-are-ordered runs
  (prop/for-all [r gr/run]
    (let [fs (rs/failures (gr/fold (:plan r) (all-events r)))
          ks (map (juxt (comp str :ns) (comp str :var)) fs)]
      (= ks (sort ks)))))

(defspec slowest-is-sorted-and-bounded runs
  (prop/for-all [r gr/run]
    (let [state (gr/fold (:plan r) (all-events r))
          top (rs/slowest state 5)
          ds (map :duration-ms top)]
      (and (<= (count top) 5)
           (= ds (sort > ds))))))

(defspec exited-workers-are-not-running runs
  (prop/for-all [r gr/run]
    (let [state (gr/fold (:plan r) (all-events r))
          exited (set (keep (fn [[id w]] (when (:exited? w) id)) (:workers state)))]
      (every? #(not (exited (:worker %))) (rs/running state)))))

(def ^:private plan {:namespaces '[a-test b-test]})

(defn- ev [m] (merge {:worker 0 :at 1000} m))

(deftest stray-output-goes-to-the-running-test
  (let [state (reduce rs/apply-event (rs/init plan)
                      [(ev {:event :ns/start :ns 'a-test})
                       (ev {:event :var/start :ns 'a-test :var 'x})
                       {:kind :output :worker 0 :text "hello"}
                       (ev {:event :assertion :ns 'a-test :var 'x :type :fail})
                       (ev {:event :var/end :ns 'a-test :var 'x :pass 0 :duration-ms 5
                            :output "captured"})])]
    (is (= "hello\ncaptured" (:output (first (rs/failures state)))))))

(deftest crash-mid-test
  (let [state (reduce rs/apply-event (rs/init plan)
                      [(ev {:event :ns/start :ns 'a-test})
                       (ev {:event :var/start :ns 'a-test :var 'x})
                       {:kind :output :worker 0 :text "boom"}
                       (ev {:event :worker/exit :exit-code 137 :at 2000})])
        [f] (rs/failures state)]
    (is (= :crash (:kind f)))
    (is (= 137 (:exit-code f)))
    (is (= 'x (:var f)))
    (is (= "boom" (:output f)))
    (is (= 1 (rs/exit-code state)))
    (is (= {:total 2 :passed 0 :failed 0 :errored 1 :running 0 :pending 1}
           (:namespaces (rs/summary state))))
    (is (empty? (rs/running state)))))

(deftest exit-code-matches-kaocha-cap
  (testing "kaocha.runner/-main*: (min (+ error fail) 255)"
    (let [fails (for [i (range 300)]
                  (ev {:event :assertion :ns 'a-test :var 'x :type :fail :line i}))
          state (reduce rs/apply-event (rs/init plan)
                        (concat [(ev {:event :ns/start :ns 'a-test})
                                 (ev {:event :var/start :ns 'a-test :var 'x})]
                                fails))]
      (is (= 255 (rs/exit-code state))))))

(deftest clean-run-exits-zero
  (let [state (reduce rs/apply-event (rs/init plan)
                      [(ev {:event :worker/ready})
                       (ev {:event :ns/start :ns 'a-test})
                       (ev {:event :var/start :ns 'a-test :var 'x})
                       (ev {:event :var/end :ns 'a-test :var 'x :pass 3 :duration-ms 7})
                       (ev {:event :ns/end :ns 'a-test :duration-ms 9})
                       (ev {:event :worker/exit :exit-code 0})])]
    (is (zero? (rs/exit-code state)))
    (is (= 1 (get-in (rs/summary state) [:namespaces :pending])))))

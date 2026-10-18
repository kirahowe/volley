(ns brisk.run-state-test
  (:require
   [brisk.gen.report :as gr]
   [brisk.run-state :as rs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.properties :as prop]))

(def ^:private runs 60)

(defn- all-events [r] (gr/events r 0))


(defspec interleaving-independence runs
  (prop/for-all [r gr/run]
    (= (gr/fold (:plan r) (gr/events r 0))
       (gr/fold (:plan r) (gr/events r 1)))))

(defn- failed-count
  "Assertions of `type` per var, the larger of the assertion events that
  arrived and the var's `:var/end` count, summed."
  [events type]
  (let [arrived (frequencies (keep #(when (and (= :assertion (:event %)) (= type (:type %)))
                                      [(:ns %) (:var %)])
                                   events))
        reported (into {} (keep #(when (= :var/end (:event %)) [[(:ns %) (:var %)] (get % type 0)]))
                       events)]
    (reduce + (map #(max (get arrived % 0) (get reported % 0))
                   (distinct (concat (keys arrived) (keys reported)))))))

(defspec summary-counts-are-consistent runs
  (prop/for-all [r gr/run]
    (let [events (all-events r)
          state (gr/fold (:plan r) events)
          s (rs/summary state)
          hidden (set (keep (fn [[ns rec]] (when (rs/unselected? rec) ns)) (:namespaces state)))
          ;; filtered vars are not part of the run; pending ones are
          started (set (keep (fn [e] (when (and (or (= :var/start (:event e))
                                                    (and (= :var/skipped (:event e))
                                                         (= :pending (:reason e))))
                                                (not (hidden (:ns e))))
                                       [(:ns e) (:var e)]))
                             events))
          {:keys [vars assertions]} s]
      (and (= (:total vars) (count started))
           (= (:total vars) (+ (:passed vars) (:failed vars) (:errored vars)
                               (:skipped vars) (:running vars)))
           (= (:total assertions) (+ (:passed assertions) (:failed assertions)
                                     (:errored assertions)))
           (= (:failed assertions) (failed-count events :fail))
           (= (:errored assertions) (failed-count events :error))
           (= (:passed assertions)
              (reduce + (keep #(when (#{:var/end :ns/end} (:event %)) (:pass %)) events)))))))

(defspec exit-code-is-zero-iff-nothing-failed runs
  (prop/for-all [r gr/run]
    (let [events (all-events r)
          state (gr/fold (:plan r) events)
          ended (set (keep #(when (= :ns/end (:event %)) (:ns %)) events))
          bad? (or (some #(or (#{:assertion :ns/error} (:event %)) (:protocol-error %)) events)
                   (some #(and (= :var/end (:event %)) (pos? (+ (:fail %) (:error %)))) events)
                   (some (complement ended) (get-in r [:plan :namespaces])))]
      (and (= (boolean bad?) (pos? (rs/exit-code state)))
           (<= (rs/exit-code state) 255)))))

(defspec failures-are-ordered runs
  (prop/for-all [r gr/run]
    (let [fs (rs/failures (gr/fold (:plan r) (all-events r)))
          ks (map (juxt (comp str :ns) (comp str :var)) fs)]
      (= ks (sort ks)))))

(defspec ns-failures-of-is-failures-filtered-by-namespace runs
  ;; The append-only reporters render one namespace per :ns/end with it.
  (prop/for-all [r gr/run]
    (let [state (gr/fold (:plan r) (all-events r))
          all (rs/failures state)]
      (every? #(= (filterv (fn [f] (= % (:ns f))) all) (rs/ns-failures-of state %))
              (distinct (concat (keys (:namespaces state)) (keep :ns all)))))))

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

(def ^:private plan {:namespaces '[a-test]})

(defn- ev [m] (merge {:worker 0 :at 1000} m))

(deftest stray-output-goes-to-the-running-test
  (testing "the pool's flat {:event :output} form is what run-state folds"
    (let [state (reduce rs/apply-event (rs/init plan)
                        [(ev {:event :ns/start :ns 'a-test})
                         (ev {:event :var/start :ns 'a-test :var 'x})
                         (ev {:event :output :text "hello" :stream :stderr})
                         (ev {:event :assertion :ns 'a-test :var 'x :type :fail})
                         (ev {:event :var/end :ns 'a-test :var 'x :pass 0 :duration-ms 5
                              :output "captured"})])]
      (is (= "hello\ncaptured" (:output (first (rs/failures state))))))))

(defn- crash-events
  "What the engine folds when worker 0 dies while running `a-test`: stray
  output, the scheduler's synthetic `:ns/error` and `:ns/end`, then the
  pool's `:worker/exit`."
  [code]
  [(ev {:event :output :text "boom" :stream :stderr})
   (ev {:event :ns/error :ns 'a-test :synthetic? true :at 2000
        :error {:class "brisk.WorkerCrashed" :message "worker process exited with code 137"
                :data nil :trace [] :exit-code code}})
   (ev {:event :ns/end :ns 'a-test :synthetic? true :crashed? true :at 2000})
   (ev {:event :worker/exit :code code :at 2000})])

(deftest crash-mid-test
  (let [state (reduce rs/apply-event (rs/init plan)
                      (concat [(ev {:event :ns/start :ns 'a-test})
                               (ev {:event :var/start :ns 'a-test :var 'x})]
                              (crash-events 137)))
        fs (rs/failures state)
        [f] fs]
    (is (= 1 (count fs)) "the synthetic :ns/error is the only source of the crash")
    (is (= :crash (:kind f)))
    (is (= 137 (:exit-code f)))
    (is (= 'x (:var f)))
    (is (= "boom" (:output f)))
    (is (= 1 (rs/exit-code state)))
    (is (= {:total 1 :passed 0 :failed 0 :errored 1 :running 0 :interrupted 0 :pending 0}
           (:namespaces (rs/summary state))))
    (is (empty? (rs/running state)))))

(deftest worker-exit-is-informational
  (testing "an idle worker exiting non-zero is not a failure"
    (let [state (reduce rs/apply-event (rs/init {:namespaces []})
                        [(ev {:event :worker/ready})
                         (ev {:event :worker/exit :code 1})])]
      (is (empty? (rs/failures state)))
      (is (zero? (rs/exit-code state)))))
  (testing "a bare :worker/exit mid-namespace does not invent a crash"
    (let [state (reduce rs/apply-event (rs/init plan)
                        [(ev {:event :ns/start :ns 'a-test})
                         (ev {:event :worker/exit :code 0})])]
      (is (empty? (rs/failures state)))
      (is (empty? (rs/running state))))))

(deftest a-namespace-with-only-errors-has-failed
  (let [state (reduce rs/apply-event (rs/init plan)
                      [(ev {:event :ns/start :ns 'a-test})
                       (ev {:event :var/start :ns 'a-test :var 'x})
                       (ev {:event :assertion :ns 'a-test :var 'x :type :error})
                       (ev {:event :var/end :ns 'a-test :var 'x :pass 0 :error 1 :duration-ms 1})
                       (ev {:event :ns/end :ns 'a-test :duration-ms 2})])]
    (is (= :errored (rs/var-status (get-in state [:namespaces 'a-test :vars 'x]))))
    (is (= :failed (rs/ns-result (get-in state [:namespaces 'a-test]))))))

(deftest planned-namespaces-that-never-finish-fail-the-run
  (testing "a worker that exits 0 holding a namespace, with no crash reported"
    (let [state (reduce rs/apply-event (rs/init {:namespaces '[a-test b-test]})
                        [(ev {:event :ns/start :ns 'a-test})
                         (ev {:event :var/start :ns 'a-test :var 'x})
                         (ev {:event :worker/exit :code 0})
                         (ev {:event :run/not-run :namespaces '[b-test] :reason :fail-fast})])]
      (is (= '{a-test :unfinished b-test :fail-fast} (rs/not-run state)))
      (is (empty? (rs/failures state)))
      (is (= 1 (rs/exit-code state)))))
  (testing "interrupted namespaces are accounted for"
    (let [state (reduce rs/apply-event (rs/init {:namespaces '[a-test]})
                        [(ev {:event :ns/start :ns 'a-test})
                         (ev {:event :run/interrupted :namespaces '[a-test] :reason :fail-fast})])]
      (is (zero? (rs/exit-code state))))))

(deftest file-errors-and-not-run-namespaces-are-failures
  (let [state (reduce rs/apply-event (rs/init plan)
                      [(ev {:event :file/error :file "test/bad_test.clj" :error "EOF"})
                       (ev {:event :run/not-run :namespaces '[a-test b-test] :reason :no-workers})])
        kinds (map :kind (rs/failures state))]
    (is (= #{:file-error :not-run} (set kinds)))
    (is (= 3 (rs/exit-code state)))
    (is (= '{a-test :no-workers b-test :no-workers} (rs/not-run state))))
  (testing "fail-fast leftovers are reported but do not count"
    (let [state (rs/apply-event (rs/init {:namespaces '[b-test]})
                                (ev {:event :run/not-run :namespaces '[b-test] :reason :fail-fast}))]
      (is (empty? (rs/failures state)))
      (is (= '{b-test :fail-fast} (rs/not-run state))))))

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
  (let [state (reduce rs/apply-event (rs/init {:namespaces '[a-test]})
                      [(ev {:event :worker/ready})
                       (ev {:event :ns/start :ns 'a-test})
                       (ev {:event :var/start :ns 'a-test :var 'x})
                       (ev {:event :var/end :ns 'a-test :var 'x :pass 3 :duration-ms 7})
                       (ev {:event :ns/end :ns 'a-test :duration-ms 9})
                       (ev {:event :worker/exit :code 0})])]
    (is (zero? (rs/exit-code state)))
    (is (= 0 (get-in (rs/summary state) [:namespaces :pending])))))

(deftest a-shutdown-hook-error-is-one-failure
  (let [err {:class "clojure.lang.ExceptionInfo" :message "post-run hook threw" :trace []}
        state (reduce rs/apply-event (rs/init {:namespaces []})
                      [{:event :worker/error :worker 2 :phase :shutdown :error err :at 1}])]
    (is (= [{:kind :worker-error :worker 2 :error err :phase :shutdown :workers [2]}] (rs/failures state)))
    (is (= 1 (rs/exit-code state)))))

(deftest the-same-shutdown-hook-failing-in-every-worker-is-one-failure
  ;; Kaocha runs post-run once, so it fails once
  (let [err {:class "clojure.lang.ExceptionInfo" :message "boom" :trace []}
        hook {:kind :post-run :entry 'my.hooks/done}
        state (reduce rs/apply-event (rs/init {:namespaces []})
                      (for [w [3 1 2]]
                        {:event :worker/error :worker w :phase :shutdown :error err :hook hook :at 1}))]
    (is (= [{:kind :worker-error :worker 1 :error err :phase :shutdown :hook hook :workers [1 2 3]}]
           (rs/failures state)))
    (is (= 1 (rs/exit-code state)))))

(deftest a-lost-assertion-still-fails-the-var
  (testing "the :var/end counts win over the assertion events that arrived"
    (let [state (reduce rs/apply-event (rs/init plan)
                        [(ev {:event :ns/start :ns 'a-test})
                         (ev {:event :var/start :ns 'a-test :var 'x})
                         (ev {:event :var/end :ns 'a-test :var 'x :pass 1 :fail 2 :error 1 :duration-ms 1})
                         (ev {:event :ns/end :ns 'a-test :duration-ms 2})])
          fs (rs/failures state)]
      (is (= :failed (rs/ns-result (get-in state [:namespaces 'a-test]))))
      (is (= {:pass 1 :fail 2 :error 1} (rs/var-counts (get-in state [:namespaces 'a-test :vars 'x]))))
      (is (= [:error :fail] (map :kind fs)))
      (is (every? #(str/includes? (:message %) "details never arrived") fs))
      (is (= 3 (rs/exit-code state)) "one per lost assertion, as Kaocha counts fail + error"))))

(deftest an-unreadable-protocol-line-is-a-failure
  (let [state (reduce rs/apply-event (rs/init plan)
                      [(ev {:event :ns/start :ns 'a-test})
                       (ev {:event :var/start :ns 'a-test :var 'x})
                       (ev {:event :output :stream :stdout :text "#brisk/event {:event :var/end ..."
                            :protocol-error "EOF while reading"})
                       (ev {:event :ns/end :ns 'a-test :duration-ms 2})])
        [f :as fs] (rs/failures state)]
    (is (= 1 (count fs)))
    (is (= {:kind :protocol-error :worker 0 :ns 'a-test :var 'x :reason "EOF while reading"}
           (dissoc f :text)))
    (is (= :failed (rs/ns-result (get-in state [:namespaces 'a-test]))))
    (is (= 1 (rs/exit-code state)))
    (is (nil? (get-in state [:namespaces 'a-test :vars 'x :stray])) "not shown as the test's output")))

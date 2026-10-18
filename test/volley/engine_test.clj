(ns volley.engine-test
  (:require
   [volley.engine :as engine]
   [volley.gen.engine :as ge]
   [volley.run-state :as rs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.properties :as prop]))

(def ^:private runs 150)
(def ^:private max-steps 4000)

(defn- printed-lines [world]
  (into [] (comp (filter #(= :print (:effect %))) (mapcat :lines) (mapcat str/split-lines))
        (:output world)))

(defn- emitted-lines
  "Permanent lines: printed ones plus the :emit part of live redraws."
  [world]
  (into (printed-lines world)
        (comp (filter #(= :redraw (:effect %))) (mapcat (comp :emit :frame)))
        (:output world)))

(defn- planned [w] (map :ns (mapcat :namespaces (get-in w [:plan :suites]))))

(defn- finished-line? [mode ns line]
  (case mode
    :documentation (re-find (re-pattern (str "^" ns "( |$)")) line)
    (re-find (re-pattern (str "^ [✓✗-] " ns " ")) line)))

(defspec loop-terminates-with-an-exit runs
  (prop/for-all [w ge/gen-world]
    (let [{:keys [world state]} (ge/simulate w max-steps)]
      (and (integer? (:exit world))
           (engine/done? state)))))

(defspec exit-code-is-the-run-states runs
  (prop/for-all [w ge/gen-world]
    (let [{:keys [world state]} (ge/simulate w max-steps)]
      (= (:exit world) (rs/exit-code (:run state))))))

(defspec every-planned-namespace-is-finished-or-not-run runs
  (prop/for-all [w ge/gen-world]
    (let [{:keys [world state]} (ge/simulate w max-steps)
          mode (get-in w [:opts :reporter])
          lines (emitted-lines world)
          not-run-lines (->> lines
                             (drop-while #(not (str/starts-with? % "Not run (")))
                             (take-while #(not (str/blank? %))))
          run (:run state)]
      (every? (fn [ns]
                (let [shown? (some #(finished-line? mode ns %) lines)
                      not-run? (some #(str/includes? % (str ns)) not-run-lines)
                      unselected? (some-> (get-in run [:namespaces ns]) rs/unselected?)]
                  (and (or shown? not-run? unselected?)
                       (not (and shown? (contains? (rs/not-run run) ns))))))
              (distinct (planned w))))))

(defspec finished-namespaces-are-printed-once runs
  (prop/for-all [w ge/gen-world]
    (let [{:keys [world]} (ge/simulate w max-steps)
          mode (get-in w [:opts :reporter])
          lines (emitted-lines world)]
      (every? (fn [ns] (<= (count (filter #(finished-line? mode ns %) lines)) 1))
              (planned w)))))

(defspec a-bad-namespace-always-fails-the-run runs
  (prop/for-all [w ge/gen-world]
    (let [{:keys [world]} (ge/simulate w max-steps)]
      (or (not-any? (comp ge/bad-fates first) (vals (:fates w)))
          (pos? (:exit world))))))

(defspec clean-shutdowns-never-fail runs
  (prop/for-all [w ge/gen-world]
    (let [w (-> w
                (assoc :fates (update-vals (:fates w) (constantly [:pass 1])))
                (assoc :spawn-fails [] :hung? false)
                (assoc-in [:plan :suites 0 :load-errors] []))
          {:keys [world]} (ge/simulate w max-steps)]
      (zero? (:exit world)))))

;;; Examples

(def ^:private help "USAGE: volley")

(defn- pre [m] (:effects (engine/preflight (merge {:cli {:options {} :errors []} :config {:errors []}
                                                   :help help :version "volley 0.1"} m))))

(defn- exit-of [effects] (:code (first (filter #(= :exit (:effect %)) effects))))

(deftest preflight-exit-codes-follow-kaocha-runner
  (testing "kaocha.runner/run: CLI errors print the usage and return -1"
    (is (= 255 (exit-of (pre {:cli {:options {} :errors ["Unknown option: \"--nope\""]}})))))
  (testing "kaocha.config/replace-by-default: invalid config exits 250, before flags are looked at"
    (is (= 250 (exit-of (pre {:config {:errors ["bad"]} :cli {:options {:help true} :errors []}})))))
  (testing "an unreadable tests.edn is a config error"
    (is (= 250 (exit-of (pre {:read-error "Failed reading tests.edn: EOF"})))))
  (testing "--help and --version exit 0"
    (is (= 0 (exit-of (pre {:cli {:options {:help true} :errors []}}))))
    (is (= 0 (exit-of (pre {:cli {:options {:version true} :errors []}})))))
  (testing "kaocha.runner/run: an unknown suite returns -2"
    (is (= 254 (exit-of (pre {:plan {:suites [] :errors ["No such suite: :nope"]}})))))
  (testing "kaocha.api/run: no tests is a warning and exit 0"
    (let [effs (pre {:plan {:suites [{:id :unit :namespaces [] :load-errors []}] :errors []}})]
      (is (= 0 (exit-of effs)))
      (is (some #(str/includes? % "No tests were found") (mapcat :lines effs)))))
  (testing "a plan with only unreadable files still runs, to report them"
    (is (nil? (exit-of (pre {:plan {:suites [{:id :unit :namespaces []
                                              :load-errors [{:file "t.clj" :error "x"}]}]
                                    :errors []}}))))))

(deftest fail-fast-leftovers-are-reported
  (let [w {:plan {:suites [{:id :unit
                            :namespaces (mapv (fn [i] {:ns (symbol (str "n" i "-test")) :select {}})
                                              (range 4))
                            :load-errors []}]}
           :fates {'n0-test [:fail] 'n1-test [:pass 1] 'n2-test [:pass 1] 'n3-test [:pass 1]}
           :opts {:workers 1 :fail-fast? true :reporter :ci :width 120 :color? false :started-at 0}
           :spawn-fails [] :hung? false :choices []}
        {:keys [world state]} (ge/simulate w max-steps)
        out (str/join "\n" (printed-lines world))]
    (is (= 1 (:exit world)))
    (is (= '{n1-test :fail-fast n2-test :fail-fast n3-test :fail-fast} (rs/not-run (:run state))))
    (is (str/includes? out "Not run (stopped by fail-fast):\n  n1-test, n2-test, n3-test"))))

(deftest workers-that-never-start-fail-the-run
  (let [w {:plan {:suites [{:id :unit :namespaces [{:ns 'a-test :select {}}] :load-errors []}]}
           :fates {'a-test [:pass 1]}
           :opts {:workers 2 :reporter :ci :width 120 :color? false :started-at 0}
           :spawn-fails (vec (repeat 10 true)) :hung? false :choices []}
        {:keys [world]} (ge/simulate w max-steps)
        out (str/join "\n" (printed-lines world))]
    (is (= 1 (:exit world)))
    (is (str/includes? out "Not run (no worker process could be started):\n  a-test"))))

(deftest hung-workers-are-killed
  (let [w {:plan {:suites [{:id :unit :namespaces [{:ns 'a-test :select {}}] :load-errors []}]}
           :fates {'a-test [:pass 1]}
           :opts {:workers 1 :reporter :ci :width 120 :color? false :started-at 0}
           :spawn-fails [] :hung? true :choices []}
        {:keys [world]} (ge/simulate w max-steps)]
    (is (pos? (:kills world 0)) "a worker that ignores the shutdown is killed by handle")
    (is (= 0 (:exit world)))))

(deftest a-worker-whose-ns-end-was-lost-is-killed-by-the-watchdog
  (let [w {:plan {:suites [{:id :unit :namespaces [{:ns 'a-test :select {}}] :load-errors []}]}
           :fates {'a-test [:garbled]}
           :opts {:workers 1 :reporter :ci :width 120 :color? false :started-at 0 :watchdog-ms 1000}
           :spawn-fails [] :hung? false :choices []}
        {:keys [world state]} (ge/simulate w max-steps)
        out (str/join "\n" (printed-lines world))
        kinds (set (map :kind (rs/failures (:run state))))]
    (is (pos? (:kills world 0)))
    (is (= 2 (:exit world)) "the unreadable line and the crash")
    (is (= #{:protocol-error :crash} kinds))
    (is (str/includes? out "volley: a message from worker 1 (running a-test) could not be read: EOF while reading"))
    (is (str/includes? out "its protocol output could not be read and it went silent"))))

(deftest a-worker-exiting-0-mid-namespace-is-a-crash
  (let [w {:plan {:suites [{:id :unit :namespaces [{:ns 'a-test :select {}}] :load-errors []}]}
           :fates {'a-test [:crash 0]}
           :opts {:workers 1 :reporter :ci :width 120 :color? false :started-at 0}
           :spawn-fails [] :hung? false :choices []}
        {:keys [world state]} (ge/simulate w max-steps)]
    (is (= [:crash] (map :kind (rs/failures (:run state)))))
    (is (= 1 (:exit world)))))

(deftest timings-are-saved
  (let [w {:plan {:suites [{:id :unit :namespaces [{:ns 'a-test :select {}}] :load-errors []}]}
           :fates {'a-test [:pass 1]}
           :opts {:workers 1 :reporter :ci :width 120 :color? false :started-at 0
                  :durations {'old-test 99}}
           :spawn-fails [] :hung? false :choices []}
        {:keys [world]} (ge/simulate w max-steps)]
    (is (= '{old-test 99 a-test 5} (:timings world)))))

(deftest the-live-view-follows-the-terminal-width
  (let [plan {:suites [{:id :unit :namespaces [{:ns 'a-very-long-namespace-name-for-a-test :select {}}]
                        :load-errors []}]}
        {:keys [state]} (engine/init plan {:workers 1 :reporter :live :width 120 :color? false :started-at 0})
        {state :state} (engine/start state)
        state (:state (engine/step state {:event :worker/ready :worker 1 :at 10}))
        state (:state (engine/step state {:event :ns/start :worker 1 :ns 'a-very-long-namespace-name-for-a-test :at 20}))
        {:keys [effects]} (engine/step state {:event :tick :at 100 :width 30})
        frame (:frame (first (filter #(= :redraw (:effect %)) effects)))]
    (is (seq (:frame frame)))
    (is (every? #(<= (count %) 30) (:frame frame)) "a tick's :width replaces the width read at the start")))

(deftest ci-mode-says-what-is-still-running
  (let [plan {:suites [{:id :unit :namespaces [{:ns 'slow-test :select {}}] :load-errors []}]}
        {:keys [state]} (engine/init plan {:workers 1 :reporter :ci :width 120 :color? false :started-at 0})
        state (:state (engine/start state))
        state (reduce #(:state (engine/step %1 %2)) state
                      [{:event :worker/ready :worker 1 :at 10}
                       {:event :ns/start :worker 1 :ns 'slow-test :at 20}
                       {:event :var/start :worker 1 :ns 'slow-test :var 'hangs :at 30}])
        lines-at (fn [st at] (let [{st :state effs :effects} (engine/step st {:event :tick :at at})]
                               [st (mapcat :lines (filter #(= :print (:effect %)) effs))]))
        [state l1] (lines-at state 20000)
        [state l2] (lines-at state 30100)
        [state l3] (lines-at state 40000)
        [_ l4] (lines-at state 60200)]
    (is (empty? l1))
    (is (= ["still running: slow-test/hangs (30.07s)"] l2))
    (is (empty? l3) "not again until another 30s have passed")
    (is (= ["still running: slow-test/hangs (1m 00s)"] l4))))

(deftest live-view-prints-uncaptured-output-in-full
  (let [line (apply str (repeat 40 "0123456789"))
        {:keys [state]} (engine/init {:suites [{:id :unit :namespaces [{:ns 'a-test}]}]}
                                     {:workers 1 :reporter :live :width 60 :started-at 0})
        state (:state (engine/step state {:event :tick :at 0}))
        state (:state (engine/step state {:event :worker/ready :worker 1 :at 1}))
        state (:state (engine/step state {:event :ns/start :ns 'a-test :run-id 1 :worker 1 :at 2}))
        state (:state (engine/step state {:event :output :text line :stream :stderr :worker 1 :at 3}))
        effects (:effects (engine/step state {:event :tick :at 100}))
        emitted (mapcat (comp :emit :frame) (filter #(= :redraw (:effect %)) effects))]
    (is (some #{line} emitted))))

(deftest load-time-output-every-worker-prints-shows-once
  ;; each worker loads the shared namespace; Kaocha loads it once
  (let [{:keys [state]} (engine/init {:suites [{:id :unit :namespaces [{:ns 'a-test} {:ns 'b-test}]}]}
                                     {:workers 2 :reporter :ci :started-at 0})
        step (fn [st ev] (engine/step (:state st) ev))
        out (fn [w text] {:event :output :text text :stream :stderr :worker w :at 3})
        results (reductions step {:state state}
                            [{:event :tick :at 0}
                             {:event :worker/ready :worker 1 :at 1}
                             {:event :worker/ready :worker 2 :at 1}
                             {:event :ns/start :ns 'a-test :run-id 1 :worker 1 :at 2}
                             {:event :ns/start :ns 'b-test :run-id 2 :worker 2 :at 2}
                             (out 1 "connecting to db")
                             (out 2 "connecting to db")
                             (out 1 "connecting to db")])
        printed (->> results (mapcat :effects) (filter #(= :print (:effect %))) (mapcat :lines))]
    (is (= 2 (count (filter #{"connecting to db"} printed)))
        "the second worker's copy is dropped; the same worker printing it again is not")))

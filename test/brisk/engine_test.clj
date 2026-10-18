(ns brisk.engine-test
  (:require
   [brisk.engine :as engine]
   [brisk.gen.engine :as ge]
   [brisk.run-state :as rs]
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
                      not-run? (some #(str/includes? % (str ns)) not-run-lines)]
                  (and (or shown? not-run?)
                       (not (and shown? (contains? (rs/not-run run) ns))))))
              (distinct (planned w))))))

(defspec finished-namespaces-are-printed-once runs
  (prop/for-all [w ge/gen-world]
    (let [{:keys [world]} (ge/simulate w max-steps)
          mode (get-in w [:opts :reporter])
          lines (emitted-lines world)]
      (every? (fn [ns] (<= (count (filter #(finished-line? mode ns %) lines)) 1))
              (planned w)))))

(defspec clean-shutdowns-never-fail runs
  (prop/for-all [w ge/gen-world]
    (let [w (-> w
                (assoc :fates (update-vals (:fates w) (constantly [:pass 1])))
                (assoc :spawn-fails [] :hung? false)
                (assoc-in [:plan :suites 0 :load-errors] []))
          {:keys [world]} (ge/simulate w max-steps)]
      (zero? (:exit world)))))

(defspec worker-ids-start-at-the-first-id-and-are-never-reused runs
  (prop/for-all [w ge/gen-world]
    (let [{:keys [world state]} (ge/simulate w max-steps)
          ids (:spawned world)
          first-id (get-in w [:opts :first-worker-id])]
      (and (every? #(>= % first-id) ids)
           (apply distinct? -1 ids)
           (= (engine/next-worker-id state) (+ first-id (count ids)))))))

(defspec events-of-workers-from-an-earlier-run-are-dropped runs
  (prop/for-all [w ge/gen-world]
    (let [{:keys [world state]} (ge/simulate w max-steps)
          run (:run state)]
      (and (not (contains? (:namespaces run) ge/stale-ns))
           (= (:exit world) (rs/exit-code run))
           (not-any? #(str/includes? % (str ge/stale-ns)) (printed-lines world))))))

(defspec fail-fast-counts-the-failures-of-one-namespace-only runs
  (prop/for-all [w ge/gen-world]
    (let [w (assoc-in w [:opts :fail-fast?] true)
          {:keys [world state]} (ge/simulate w max-steps)
          run (:run state)
          failing (->> (rs/failures run)
                       (remove #(#{:file-error :not-run} (:kind %)))
                       (map :ns)
                       set)
          interrupted (set (keys (rs/interrupted run)))]
      (and (<= (count failing) 1)
           (not-any? interrupted failing)
           (not-any? #(contains? (rs/not-run run) %) interrupted)
           (= (:exit world) (rs/exit-code run))))))

(defspec every-worker-is-configured-before-its-first-run runs
  (prop/for-all [w ge/gen-world]
    (let [{:keys [world]} (ge/simulate w max-steps)]
      (if (get-in w [:opts :configure])
        (= (seq (:spawned world)) (seq (:configured world)))
        (empty? (:configured world))))))

(defspec post-summary-hooks-run-once-before-the-exit runs
  (prop/for-all [w ge/gen-world]
    (let [{:keys [world]} (ge/simulate w max-steps)]
      (if (get-in w [:opts :post-summary])
        (and (:post-summary-before-exit? world)
             (= '[my/summary] (get-in world [:post-summary :hooks]))
             (vector? (get-in world [:post-summary :result :kaocha.result/tests])))
        (nil? (:post-summary world))))))

;;; Examples

(def ^:private help "USAGE: brisk")

(defn- pre [m] (:effects (engine/preflight (merge {:cli {:options {} :errors []} :config {:errors []}
                                                   :help help :version "brisk 0.1"} m))))

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

(deftest timings-are-saved
  (let [w {:plan {:suites [{:id :unit :namespaces [{:ns 'a-test :select {}}] :load-errors []}]}
           :fates {'a-test [:pass 1]}
           :opts {:workers 1 :reporter :ci :width 120 :color? false :started-at 0
                  :durations {'old-test 99}}
           :spawn-fails [] :hung? false :choices []}
        {:keys [world]} (ge/simulate w max-steps)]
    (is (= '{old-test 99 a-test 5} (:timings world)))))

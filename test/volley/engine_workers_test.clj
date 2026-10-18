(ns volley.engine-workers-test
  "Properties of the engine's loop about workers: their ids, events of
  workers from an earlier run, configuration, fail-fast and the hooks
  that run in the parent. Split from `volley.engine-test` so the two run
  side by side."
  (:require
   [volley.engine :as engine]
   [volley.gen.engine :as ge]
   [volley.run-state :as rs]
   [clojure.string :as str]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.properties :as prop]))

(def ^:private runs 150)
(def ^:private max-steps 4000)

(defn- printed-lines [world]
  (into [] (comp (filter #(= :print (:effect %))) (mapcat :lines) (mapcat str/split-lines))
        (:output world)))

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

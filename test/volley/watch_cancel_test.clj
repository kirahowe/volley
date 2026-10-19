(ns volley.watch-cancel-test
  "`volley.watch/cancel-action`: a cancelled run's workers are asked to
  stop, killed after the grace period, and given up on after that."
  (:require
   [volley.gen.watch :as gw]
   [volley.watch :as watch]
   [clojure.test :refer [deftest is]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.properties :as prop]))

(defspec a-cancel-is-done-exactly-when-no-worker-is-left 200
  (prop/for-all [p gw/cancel-progress]
    (= (empty? (:waiting p)) (= :done (watch/cancel-action p)))))

(defspec workers-are-killed-once-and-only-after-the-grace-period 200
  (prop/for-all [p gw/cancel-progress]
    (= (= :kill (watch/cancel-action p))
       (boolean (and (seq (:waiting p)) (not (:killed? p)) (>= (:elapsed p) watch/cancel-grace-ms))))))

(defspec a-cancel-gives-up-only-after-killing 200
  (prop/for-all [p gw/cancel-progress]
    (or (not= :give-up (watch/cancel-action p))
        (and (:killed? p) (> (:elapsed p) watch/cancel-grace-ms)))))

(deftest a-cancel-waits-then-kills-then-gives-up
  (let [at (fn [ms killed?] (watch/cancel-action {:elapsed ms :waiting #{1} :killed? killed?}))]
    (is (= :wait (at 0 false)))
    (is (= :wait (at (dec watch/cancel-grace-ms) false)))
    (is (= :kill (at watch/cancel-grace-ms false)))
    (is (= :wait (at (inc watch/cancel-grace-ms) true)))
    (is (= :give-up (at 60000 true)))))

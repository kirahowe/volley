(ns gs.a-fails-test
  (:require [clojure.test :refer [deftest is]]
            [gs.hooks :as hooks]))

(defn- await-started
  "Wait up to 30s for the other namespaces' workers to begin a var."
  []
  (let [deadline (+ (System/currentTimeMillis) 30000)]
    (while (and (not (every? #(.exists (java.io.File. (str "markers/started-" %)))
                             ['gs.steps-test 'gs.hung-test]))
                (< (System/currentTimeMillis) deadline))
      (Thread/sleep 20))))

;; Fail only once the other workers are busy, so fail-fast interrupts them.
(deftest fails (hooks/bump! 'gs.a-fails-test) (await-started) (Thread/sleep 300) (is (= 1 2)))

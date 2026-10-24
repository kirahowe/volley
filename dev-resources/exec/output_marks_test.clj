(ns exec.output-marks-test
  (:require [clojure.test :refer [deftest is testing]]))

(deftest fails-between-prints
  (testing "early"
    (println "before")
    (is (= 1 2)))
  (testing "later"
    (println "after 1")
    (println "after 2")
    (is (= 3 4))
    (println "after 3")))

(ns exec.pass-test
  (:require [clojure.test :refer [deftest is testing]]))

(deftest adds
  (is (= 4 (+ 2 2)))
  (is (= 6 (* 2 3))))

(deftest strings
  (testing "upper"
    (is (= "A" (.toUpperCase "a"))))
  (is (re-find #"b" "abc")))

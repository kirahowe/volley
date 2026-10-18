(ns acme.math-test
  (:require [acme.math :as m]
            [clojure.test :refer [deftest is testing]]))

(deftest adds
  (is (= 4 (m/add 2 2)))
  (is (= 0 (m/add -1 1))))

(deftest divides
  (testing "whole numbers"
    (is (= 2 (m/div 4 2))))
  (testing "ratios"
    (is (= 1/2 (m/div 1 2)))))

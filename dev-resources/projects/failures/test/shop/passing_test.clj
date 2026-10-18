(ns shop.passing-test
  (:require [clojure.test :refer [deftest is]]))

(deftest one (is (= 1 1)))
(deftest two (is (= 2 2)) (is (= 3 3)))

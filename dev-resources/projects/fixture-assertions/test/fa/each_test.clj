(ns fa.each-test
  "Asserts in an :each fixture, after the test it wraps."
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each (fn [f] (f) (is (= 1 2) "post-check in an :each fixture")))

(deftest a (is true))

(deftest b (is true))

(ns fa.once-test
  "Asserts in a :once fixture, outside every test."
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once (fn [f] (is (= 1 2) "pre-check in a :once fixture") (is true) (f)))

(deftest a (is true))

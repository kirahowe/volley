(ns za.fixture-test
  "An assertion in an :each fixture counts for the var it wraps."
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each (fn [f] (is true "from the fixture") (f)))

(deftest body-asserts-nothing)

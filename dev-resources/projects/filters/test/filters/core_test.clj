(ns filters.core-test
  (:require [clojure.test :refer [deftest is]]))

(deftest plain (is true))
(deftest ^:wip in-progress (is (= 1 1)))
(deftest ^:slow takes-ages (is true))
(deftest ^:flaky sometimes (is true))
(deftest ^:kaocha/skip skipped-by-default (is false))

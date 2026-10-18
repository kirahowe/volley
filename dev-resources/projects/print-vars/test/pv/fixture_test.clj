(ns pv.fixture-test
  "A fixture that keeps huge values out of the log by binding the print vars."
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once (fn [f] (binding [*print-length* 5 *print-level* 3] (f))))

(deftest broken (is (= 1 2)))

(deftest also-broken (throw (ex-info "boom" {})))

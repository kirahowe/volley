(ns ^:kaocha/pending exec.ns-pending-test
  (:require [clojure.test :refer [deftest is use-fixtures]]))

;; Kaocha runs nothing of a pending namespace, its fixtures included.
(use-fixtures :once (fn [_] (throw (ex-info "a pending namespace ran its :once fixture" {}))))

(deftest whole-ns-is-pending (is false))

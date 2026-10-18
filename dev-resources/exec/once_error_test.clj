(ns exec.once-error-test
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once (fn [_f] (throw (ex-info "once fixture failed" {}))))

(deftest unreachable (is true))

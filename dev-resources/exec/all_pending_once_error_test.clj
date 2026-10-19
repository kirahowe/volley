(ns exec.all-pending-once-error-test
  "Every var is pending and the :once fixture throws."
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once (fn [_] (throw (ex-info "once fixture failed" {}))))

(deftest ^:kaocha/pending not-written-yet (is false))

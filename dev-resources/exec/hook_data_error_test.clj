(ns exec.hook-data-error-test
  (:require [clojure.test :refer [deftest is use-fixtures]]))

;; a test's own error whose data happens to have a :hook key
(use-fixtures :once (fn [_f] (throw (ex-info "webhook failed" {:hook :github}))))

(deftest unreachable (is true))

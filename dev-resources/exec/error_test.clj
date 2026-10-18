(ns exec.error-test
  (:require [clojure.test :refer [deftest is]]))

(deftest throws
  (is (= 1 1))
  (throw (ex-info "boom" {:why :testing})))

(deftest throws-inside-is
  (is (= 1 (Integer/parseInt "nope"))))

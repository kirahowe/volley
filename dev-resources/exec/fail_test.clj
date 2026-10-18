(ns exec.fail-test
  (:require [clojure.test :refer [deftest is]]))

(deftest nested-mismatch
  (is (= {:a [1 2 {:b 3}] :c #{1 2}}
         {:a [1 2 {:b 4}] :c #{1 2}}))
  (is (= 1 1)))

(deftest passes
  (is (true? true)))

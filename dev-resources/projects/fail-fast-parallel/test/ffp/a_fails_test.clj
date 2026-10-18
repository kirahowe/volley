(ns ffp.a-fails-test
  (:require [clojure.test :refer [deftest is]]))

;; Give the slow namespaces time to start before the failure arrives.
(deftest first-failure (Thread/sleep 1500) (is (= 1 2)))
(deftest second-is-never-run (is true))

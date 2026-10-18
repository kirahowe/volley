(ns exec.slow-c-test
  (:require [clojure.test :refer [deftest is]]))

(deftest sleeps
  (Thread/sleep 400)
  (is true))

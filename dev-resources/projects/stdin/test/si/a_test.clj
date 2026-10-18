(ns si.a-test
  "Tests that read stdin, which carries the worker's commands."
  (:require [clojure.test :refer [deftest is]]))

(deftest reads-system-in (is (= -1 (.read System/in))))

(deftest reads-a-line (is (nil? (read-line))))

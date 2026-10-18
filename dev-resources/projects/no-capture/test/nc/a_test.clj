(ns nc.a-test
  (:require [clojure.test :refer [deftest is]]))

(println "printed at load time")

(deftest a
  (println "debug print from a passing test")
  (is true))

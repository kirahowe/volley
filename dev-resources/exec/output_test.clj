(ns exec.output-test
  (:require [clojure.test :refer [deftest is]]))

(deftest prints-via-println
  (println "hello from println")
  (binding [*out* *err*] (println "hello on err"))
  (is true))

(deftest prints-via-system-out
  (.println System/out "direct system out")
  (.print System/out "partial ")
  (.flush System/out)
  (is (= 1 2)))

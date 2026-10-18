(ns za.plain-test
  "Kaocha fails a test var that makes no assertion (kaocha.type.var)."
  (:require [clojure.test :refer [deftest is]]))

(deftest no-assertion)

(deftest prints-but-asserts-nothing (println "busy"))

(deftest asserts (is true))

(deftest ^:kaocha/pending pending-without-assertion)

(deftest ^:skipme skipped-without-assertion)

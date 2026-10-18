(ns exec.pending-test
  (:require [clojure.test :refer [deftest is]]))

(deftest ^:kaocha/pending not-written-yet (is false))
(deftest ^:slow marked-slow (is true))
(deftest runs (is true))

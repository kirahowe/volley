(ns exec.skip-test
  (:require [clojure.test :refer [deftest is]]))

(deftest ^:slow marked-slow (is true))
(deftest normal (is true))

(ns ^:synchronized exec.sync-test
  (:require [clojure.test :refer [deftest is]]))

(deftest runs-alone
  (is (= 2 (inc 1))))

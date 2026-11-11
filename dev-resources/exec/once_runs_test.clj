(ns exec.once-runs-test
  "Two passing vars inside a :once fixture that counts its runs."
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(def once-runs (atom 0))

(use-fixtures :once (fn [f] (swap! once-runs inc) (f)))

(deftest one (is true))

(deftest two (is true))

(ns exec.all-pending-test
  "Every var is pending. Kaocha still runs the :once fixtures, but no
  :each fixture, since it never runs a pending var."
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(def once-runs (atom 0))

(use-fixtures :once (fn [f] (swap! once-runs inc) (f)))

(use-fixtures :each (fn [_] (throw (ex-info "an :each fixture ran for a pending var" {}))))

(deftest ^:kaocha/pending not-written-yet (is false))

(deftest ^:kaocha/pending nor-this-one (is false))

(ns hooks.a-test
  (:require [clojure.test :refer [deftest is]]
            [hooks.probe :as probe]))

(deftest worker-setup-ran-first (is (true? @probe/setup-done)))
(deftest defused-by-pre-report (is false "defuse me"))
(deftest ^:skip-by-hook skipped-by-pre-test (is false "never runs"))

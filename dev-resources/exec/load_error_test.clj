(ns exec.load-error-test
  (:require [clojure.test :refer [deftest is]]
            [exec.does-not-exist]))

(deftest never-runs (is true))

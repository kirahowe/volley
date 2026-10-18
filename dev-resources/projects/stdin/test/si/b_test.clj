(ns si.b-test
  "Runs after si.a-test in the same worker."
  (:require [clojure.test :refer [deftest is]]))

(deftest after (is true))

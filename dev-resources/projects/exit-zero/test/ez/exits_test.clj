(ns ez.exits-test
  "Ends the process with a success code in the middle of the run."
  (:require [clojure.test :refer [deftest is]]))

(deftest exits-cleanly
  (is true)
  (System/exit 0))

(deftest never-reached (is true))

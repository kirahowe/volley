(ns ffp.slow-1-test
  (:require [clojure.test :refer [deftest is]]))

(deftest quick-1 (is true))
(deftest takes-a-minute-1 (Thread/sleep 60000) (is true))

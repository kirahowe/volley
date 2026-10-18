(ns ffp.slow-2-test
  (:require [clojure.test :refer [deftest is]]))

(deftest quick-2 (is true))
(deftest takes-a-minute-2 (Thread/sleep 60000) (is true))

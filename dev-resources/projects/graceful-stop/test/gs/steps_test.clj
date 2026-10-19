(ns gs.steps-test
  "Twenty vars of 200ms each: a worker asked to stop finishes the current
  one and skips the rest."
  (:require [clojure.test :refer [deftest is]]
            [gs.hooks :as hooks]))

(defn- step [] (hooks/bump! 'gs.steps-test) (Thread/sleep 200) (is true))

(deftest step-00 (step))
(deftest step-01 (step))
(deftest step-02 (step))
(deftest step-03 (step))
(deftest step-04 (step))
(deftest step-05 (step))
(deftest step-06 (step))
(deftest step-07 (step))
(deftest step-08 (step))
(deftest step-09 (step))
(deftest step-10 (step))
(deftest step-11 (step))
(deftest step-12 (step))
(deftest step-13 (step))
(deftest step-14 (step))
(deftest step-15 (step))
(deftest step-16 (step))
(deftest step-17 (step))
(deftest step-18 (step))
(deftest step-19 (step))

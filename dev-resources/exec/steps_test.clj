(ns exec.steps-test
  "Ten vars of 200ms each, to stop a worker in the middle of."
  (:require [clojure.test :refer [deftest is]]))

(defn- step [] (Thread/sleep 200) (is true))

(deftest step-0 (step))
(deftest step-1 (step))
(deftest step-2 (step))
(deftest step-3 (step))
(deftest step-4 (step))
(deftest step-5 (step))
(deftest step-6 (step))
(deftest step-7 (step))
(deftest step-8 (step))
(deftest step-9 (step))

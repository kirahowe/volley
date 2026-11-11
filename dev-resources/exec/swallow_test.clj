(ns exec.swallow-test
  "A first var that swallows the interrupt of a kill, then two more, and a
  post-run hook that counts its calls."
  (:require [clojure.test :refer [deftest is]]))

(def post-runs (atom 0))

(defn count-post-run [result]
  (swap! post-runs inc)
  result)

(deftest a-swallows-the-interrupt
  (try (Thread/sleep 1000) (catch InterruptedException _ nil))
  (is true))

(deftest b-after (is true))

(deftest c-after (is true))

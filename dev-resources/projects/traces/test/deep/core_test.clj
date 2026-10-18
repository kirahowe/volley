(ns deep.core-test
  (:require [clojure.test :refer [deftest is]]
            [deep.core :as core]))

(deftest divides-by-zero-three-calls-down
  (is (= 1 (core/outer 1))))

(deftest throws-outside-an-assertion
  (core/relay-explosion {:id 7}))

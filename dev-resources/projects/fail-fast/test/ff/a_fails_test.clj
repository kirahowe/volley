(ns ff.a-fails-test
  (:require [clojure.test :refer [deftest is]]))

(deftest first-failure (is (= 1 2)))
(deftest after-the-failure (is true))
(deftest later-in-the-namespace (is true))

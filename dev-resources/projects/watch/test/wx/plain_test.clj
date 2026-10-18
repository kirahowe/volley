(ns wx.plain-test
  (:require [clojure.test :refer [deftest is]]))

(deftest arithmetic (is (= 2 (+ 1 1))))

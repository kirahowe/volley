(ns g.only-gen-test
  "Only macro-defined tests."
  (:require [clojure.test :refer [deftest is]]))

(defmacro defcase [n v & [m]]
  `(deftest ~(vary-meta n merge m) (is (= 1 ~v))))

(defcase gen-integration 1 {:integration true})

(defcase gen-plain 1)

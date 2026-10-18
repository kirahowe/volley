(ns g.gen-test
  "Tests defined by a macro, which brisk's parser cannot see."
  (:require [clojure.test :refer [deftest is]]))

(defmacro defcase [n v & [m]]
  `(deftest ~(vary-meta n merge m) (is (= 1 ~v))))

(deftest literal (is true))

(defcase generated 1 {:integration true})

(defcase generated-fail 2)

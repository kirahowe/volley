(ns pv.root-test
  "Changes the print vars for the rest of the worker's life."
  (:require [clojure.test :refer [deftest is]]))

(set! *print-length* 3)

(deftest ok (is (= 1 1)))

(deftest fails (is (= [1 2 3 4] [1 2 3 5])))

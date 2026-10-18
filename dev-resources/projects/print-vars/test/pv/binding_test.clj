(ns pv.binding-test
  (:require [clojure.test :refer [deftest is]]))

(deftest bound-print-length
  (binding [*print-length* 2]
    (is (= [1 2 3] [1 2 4]))))

(deftest bound-print-level
  (binding [*print-level* 1]
    (is (= {:a {:b 1}} {:a {:b 2}}))))

(deftest other-print-vars
  (binding [*print-dup* true *print-meta* true *print-readably* nil *print-namespace-maps* true]
    (is (= {:a/b "x"} {:a/b "y"}))))

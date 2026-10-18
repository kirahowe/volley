(ns et.a-test
  "An :each fixture that throws before the second test."
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(def calls (atom 0))

(use-fixtures :each (fn [f]
                      (when (= 2 (swap! calls inc))
                        (throw (ex-info "fixture boom on the 2nd test" {})))
                      (f)))

(deftest a (is true))

(deftest b (is true))

(deftest c (is true))

(deftest d (is true))

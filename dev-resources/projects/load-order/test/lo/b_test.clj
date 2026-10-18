(ns lo.b-test
  (:require [clojure.test :refer [deftest is]]))

(deftest b (is (roughly= 2.0 2.001)))

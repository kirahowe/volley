(ns ordered.a-test
  (:require [clojure.test :refer [deftest is]]))

(deftest a (Thread/sleep 50) (is true))

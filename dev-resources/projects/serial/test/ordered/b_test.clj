(ns ordered.b-test
  (:require [clojure.test :refer [deftest is]]))

(deftest b (Thread/sleep 50) (is true))

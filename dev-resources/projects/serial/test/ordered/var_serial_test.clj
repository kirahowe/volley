(ns ordered.var-serial-test
  (:require [clojure.test :refer [deftest is]]))

(deftest ^:synchronized also-alone (is (= 2 2)))

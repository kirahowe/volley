(ns he.a-test (:require [clojure.test :refer [deftest is]]))

(deftest t1 (Thread/sleep 300) (is true))

(ns he.b-test (:require [clojure.test :refer [deftest is]]))

(deftest t1 (Thread/sleep 300) (is true))

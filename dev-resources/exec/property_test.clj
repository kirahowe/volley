(ns exec.property-test
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(defspec small-numbers-only {:num-tests 50 :seed 42}
  (prop/for-all [n gen/nat]
    (< n 3)))

(ns shop.missing-dep-test
  (:require [clojure.test :refer [deftest is]]
            [shop.does-not-exist]))

(deftest never-runs (is true))

(ns filters.skipped-test
  (:require [clojure.test :refer [deftest is]]))

(deftest skipped-by-config (is false))

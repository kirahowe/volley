(ns exec.exit-test
  (:require [clojure.test :refer [deftest is]]))

(deftest before (is true))

(deftest kills-the-worker
  (System/exit 3))

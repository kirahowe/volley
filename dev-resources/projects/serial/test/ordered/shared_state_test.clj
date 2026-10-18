(ns ^:synchronized ordered.shared-state-test
  "Uses a fixed port, so it must not overlap other namespaces."
  (:require [clojure.test :refer [deftest is]]))

(deftest alone (is (= 1 1)))

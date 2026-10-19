(ns ^:kaocha/pending pf.whole-ns-test
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once (fn [f] (spit "whole-ns.ran" "") (f)))

(deftest not-yet (is false))

(ns pf.all-pending-test
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once (fn [f] (spit "all-pending.ran" "") (f)))

(deftest ^:kaocha/pending not-yet (is false))

(ns exec.fixtures-test
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(def ^:dynamic *each* nil)
(def once-count (atom 0))

(use-fixtures :once
  ;; Reset afterwards so a second run in the same process counts afresh.
  (fn [f] (swap! once-count inc) (f) (reset! once-count 0)))

(use-fixtures :each
  (fn [f] (binding [*each* :set] (f))))

(deftest sees-each-fixture
  (is (= :set *each*)))

(deftest once-ran-exactly-once
  (is (= 1 @once-count))
  (is (= :set *each*)))

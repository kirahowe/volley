(ns exec.contexts-test
  (:require [clojure.test :refer [deftest is testing assert-expr do-report]]))

(defmethod assert-expr 'always-fails? [msg form]
  `(do-report {:type :fail :message ~msg :expected '~form :actual :custom}))

(deftest nested-contexts
  (testing "outer"
    (testing "inner"
      (is (= 1 2) "with message"))))

(deftest custom-assert
  (is (always-fails? 1)))

(ns shop.errors-test
  (:require [clojure.test :refer [deftest is]]))

(deftest throws-outside-is
  (is true)
  (throw (ex-info "inventory unavailable" {:sku 42})))

(deftest throws-inside-is
  (is (= 1 (Long/parseLong "one"))))

(deftest fine (is (= 2 (inc 1))))

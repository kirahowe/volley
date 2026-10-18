(ns app.parse-test
  (:require [clojure.test :refer [deftest is]]))

(deftest parses-ints (is (= 12 (parse-long "12"))))
(deftest parses-nil (is (nil? (parse-long "x"))))

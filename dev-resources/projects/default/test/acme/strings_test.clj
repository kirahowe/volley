(ns acme.strings-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(deftest upper (is (= "ABC" (str/upper-case "abc"))))
(deftest wrong (is (= "abc" (str/reverse "abc"))))

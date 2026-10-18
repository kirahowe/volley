(ns pb.b-test
  "Reads a property at load time that suite :b's worker setup sets."
  (:require [clojure.test :refer [deftest is]]))

(defonce url (System/getProperty "volley.preload.url"))

(deftest b (is (= "jdbc:test" url) "loaded before suite :b's worker setup"))

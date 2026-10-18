(ns brisk.version-test
  "The version has one source, `brisk/version.edn`; the CHANGELOG's newest
  entry and the README name the same one."
  (:require
   [brisk.main :as main]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest one-version-everywhere
  (let [v (:version (edn/read-string (slurp (io/resource "brisk/version.edn"))))]
    (is (= (str "brisk " v) main/version))
    (is (= v (second (re-find #"(?m)^## \[([^\]]+)\]" (slurp "CHANGELOG.md")))))
    (is (str/includes? (slurp "README.md") (str "(" v ")")))))

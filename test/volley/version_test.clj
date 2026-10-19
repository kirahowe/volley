(ns volley.version-test
  "The version has one source, `volley/version.edn`; the CHANGELOG's newest
  released entry and the README name the same one."
  (:require
   [volley.main :as main]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn- changelog-version
  "The version in the first `## [...]` heading of `changelog`, skipping an
  `## [Unreleased]` section."
  [changelog]
  (second (re-find #"(?m)^## \[(?!Unreleased\])([^\]]+)\]" changelog)))

(deftest changelog-version-skips-unreleased
  (is (= "1.2.0" (changelog-version "# Changelog\n\n## [Unreleased]\n\n- x\n\n## [1.2.0] - 2026-01-01\n")))
  (is (= "1.2.0" (changelog-version "# Changelog\n\n## [1.2.0] - 2026-01-01\n"))))

(deftest one-version-everywhere
  (let [v (:version (edn/read-string (slurp (io/resource "volley/version.edn"))))]
    (is (= (str "volley " v) main/version))
    (is (= v (changelog-version (slurp "CHANGELOG.md"))))
    (is (str/includes? (slurp "README.md") (str "(" v ")")))))

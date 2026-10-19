(ns volley.gen.ns-cache
  "Generators for `volley.ns-cache`: files as path and SHA, and caches
  left by an earlier run."
  (:require
   [clojure.test.check.generators :as gen]))

(def path
  "A test file path from a small pool, so files and caches overlap."
  (gen/elements ["test/a_test.clj" "test/b_test.clj" "test/c_test.clj" "test/d_test.clj"]))

(def sha
  "A file's hash, from a small pool, so some files are unchanged."
  (gen/elements ["s1" "s2" "s3"]))

(def files
  "The files of a run, `[{:path p :sha hex}]`, each path once."
  (gen/fmap (fn [m] (mapv (fn [[p s]] {:path p :sha s}) m))
            (gen/map path sha {:max-elements 4})))

(def parser-key
  "The parser's cache key."
  (gen/elements ["k1" "k2"]))

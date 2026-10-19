(ns volley.ns-cache-test
  (:require
   [volley.gen.ns-cache :as g]
   [volley.ns-cache :as ns-cache]
   [clojure.test :refer [deftest is]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

(defn- parse
  "A stand-in for `volley.discover/ns-info`: a function of path and text,
  here of path and SHA."
  [path sha]
  {:file path :ns (symbol (str path "-" sha))})

(defn- cacheable?
  "Some infos do not survive an EDN round trip."
  [info]
  (not (re-find #"d_test" (:file info))))

(defn- run
  "The infos of `files` from `cache`, and the cache to save, if any, as the
  shell gets them."
  [cache key files]
  (let [{to-parse :parse :keys [reuse write?]} (ns-cache/plan-reuse cache key files)
        sha-of (into {} (map (juxt :path :sha)) files)
        parsed (into {} (map (juxt identity #(parse % (sha-of %)))) to-parse)
        infos (mapv #(get reuse (:path %) (get parsed (:path %))) files)]
    {:infos infos
     :parsed (set to-parse)
     :saved (when write? (ns-cache/next-cache key (map #(assoc %1 :info %2) files infos) cacheable?))}))

(def ^:private earlier-cache
  "A cache an earlier run saved."
  (gen/fmap (fn [[key files]] (:saved (run {} key files))) (gen/tuple g/parser-key g/files)))

(defspec reused-and-parsed-infos-are-those-of-parsing-every-file 300
  (prop/for-all [cache earlier-cache key g/parser-key files g/files]
    (= (mapv #(parse (:path %) (:sha %)) files)
       (:infos (run cache key files)))))

(defspec an-unchanged-run-saves-nothing-and-parses-only-uncacheable-files 300
  (prop/for-all [cache earlier-cache key g/parser-key files g/files]
    (let [saved (or (:saved (run cache key files)) cache)
          again (run saved key files)]
      (and (nil? (:saved again))
           (= (:parsed again)
              (set (keep #(when-not (cacheable? (parse (:path %) (:sha %))) (:path %)) files)))))))

(defspec a-changed-file-is-parsed-and-saved 300
  (prop/for-all [cache earlier-cache key g/parser-key files (gen/not-empty g/files)]
    (let [saved (or (:saved (run cache key files)) cache)
          changed (update files 0 assoc :sha "new")
          after (run saved key changed)]
      (and (contains? (:parsed after) (:path (first files)))
           (some? (:saved after))))))

(deftest another-parser-reuses-nothing
  (let [cache (:saved (run {} "k1" [{:path "test/a_test.clj" :sha "s1"}]))
        plan (ns-cache/plan-reuse cache "k2" [{:path "test/a_test.clj" :sha "s1"}])]
    (is (= {:reuse {} :parse ["test/a_test.clj"] :write? true} plan))))

(deftest a-removed-file-rewrites-the-cache
  (let [files [{:path "test/a_test.clj" :sha "s1"} {:path "test/b_test.clj" :sha "s1"}]
        cache (:saved (run {} "k1" files))]
    (is (:write? (ns-cache/plan-reuse cache "k1" (take 1 files))))))

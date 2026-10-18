(ns brisk.jvm-e2e-test
  "brisk with JVM workers from a babashka parent (`--runtime jvm`), end
  to end on dev-resources/projects/jvm, and error traces on both
  runtimes. `brisk.e2e.jvm-parent-test` covers a parent on the JVM."
  (:require
   [babashka.fs :as fs]
   [brisk.e2e.support :as e2e]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private expected-summary "4 tests | 4 passed | 0 failed | 0 errors | 0 skipped")

(defn- summary [out]
  (some-> (e2e/summary-line out) (str/replace #" \(.*\)$" "")))

(defn- cache-files [dir]
  (vec (sort (map (comp str fs/file-name) (fs/glob (fs/path dir ".brisk") "classpath-*.txt")))))

(deftest bb-parent-with-jvm-workers
  (let [dir (e2e/copy-project "jvm")
        env {"EXPECT_FIXTURE_PROP" "alias"}
        run (fn [& args] (e2e/brisk dir (into ["--runtime" "jvm"] args) {:env env :timeout-ms 180000}))]
    (testing "the :test alias is the default: its path and :jvm-opts reach the worker"
      (let [{:keys [exit out err]} (run)]
        (is (= 0 exit) (str out err))
        (is (= expected-summary (summary out)))))
    (testing "the classpath is cached under .brisk, keyed on deps.edn"
      (is (= 1 (count (cache-files dir)))))
    (testing "a second run reuses the cache"
      (let [file (first (fs/glob (fs/path dir ".brisk") "classpath-*.txt"))
            before (fs/last-modified-time file)
            {:keys [exit out]} (run)]
        (is (= 0 exit))
        (is (= expected-summary (summary out)))
        (is (= before (fs/last-modified-time file)))))
    (testing "changing deps.edn computes a new classpath"
      (spit (str (fs/path dir "deps.edn"))
            (str (slurp (str (fs/path dir "deps.edn"))) "\n;; changed\n"))
      (is (= 0 (:exit (run))))
      (is (= 2 (count (cache-files dir)))))))

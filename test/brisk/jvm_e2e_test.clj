(ns brisk.jvm-e2e-test
  "brisk with JVM workers, end to end on dev-resources/projects/jvm: from a
  babashka parent with `--runtime jvm`, and from a parent on the JVM
  started by the Clojure CLI."
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
      (is (= 2 (count (cache-files dir)))))
    (testing "--aliases picks other aliases; a missing :test alias would lose the helper"
      (spit (str (fs/path dir "deps.edn"))
            "{:paths [\"src\"] :aliases {:other {:extra-paths [\"extra\"] :jvm-opts [\"-Dbrisk.fixture=other\"]}}}")
      (let [{:keys [exit out]} (e2e/brisk dir ["--runtime" "jvm" "--aliases" ":other"]
                                          {:env {"EXPECT_FIXTURE_PROP" "other"} :timeout-ms 180000})]
        (is (= 0 exit) out)
        (is (= expected-summary (summary out)))))))

(deftest bb-parent-reports-an-unusable-classpath
  (let [dir (e2e/copy-project "jvm" {"deps.edn" "{:deps {no.such/lib {:mvn/version \"0.0.0-none\"}}}"})
        {:keys [exit err]} (e2e/brisk dir ["--runtime" "jvm"] {:timeout-ms 180000})]
    (is (= 250 exit))
    (is (str/includes? err "clojure -Spath"))))

(deftest jvm-parent-starts-jvm-workers-with-its-options
  (let [dir (e2e/copy-project "jvm")
        deps (pr-str {:paths ["src" "extra"]
                      :deps {'brisk/brisk {:local/root (str (fs/absolutize "."))}}})
        {:keys [exit out err]}
        (e2e/sh dir ["clojure" "-Sdeps" deps "-J-Dbrisk.fixture=parent" "-J-Xmx384m"
                     "-M" "-m" "brisk.main"]
                {:env {"EXPECT_FIXTURE_PROP" "parent" "EXPECT_MAX_HEAP_MB" "384"}
                 :timeout-ms 180000})]
    (is (= 0 exit) (str out err))
    (is (= expected-summary (summary out)))))

(deftest jvm-parent-can-still-start-bb-workers
  (let [dir (e2e/copy-project "pending")
        deps (pr-str {:deps {'brisk/brisk {:local/root (str (fs/absolutize "."))}}})
        {:keys [exit out]} (e2e/sh dir ["clojure" "-Sdeps" deps "-M" "-m" "brisk.main"
                                        "--runtime" "bb" "--print-result"]
                                   {:timeout-ms 180000})]
    (is (= 0 exit))
    (is (map? (e2e/last-edn-line out "{:vars"))))
)

(ns volley.e2e.jvm-parent-test
  "volley with its parent on the JVM, started by the Clojure CLI, end to
  end on the fixture projects."
  (:require
   [babashka.fs :as fs]
   [volley.e2e.support :as e2e]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(def ^:private expected-summary "4 tests | 4 passed | 0 failed | 0 errors | 0 skipped")

(defn- summary [out]
  (some-> (e2e/summary-line out) (str/replace #" \(.*\)$" "")))

(deftest jvm-parent-starts-jvm-workers-with-its-options
  (let [dir (e2e/copy-project "jvm")
        deps (pr-str {:paths ["src" "extra"]
                      :deps {'volley/volley {:local/root (str (fs/absolutize "."))}}})
        {:keys [exit out err]}
        (e2e/sh dir ["clojure" "-Sdeps" deps "-J-Dvolley.fixture=parent" "-J-Xmx384m"
                     "-M" "-m" "volley.main"]
                {:env {"EXPECT_FIXTURE_PROP" "parent" "EXPECT_MAX_HEAP_MB" "384"}
                 :timeout-ms 180000})]
    (is (= 0 exit) (str out err))
    (is (= expected-summary (summary out)))))

(deftest jvm-parent-can-still-start-bb-workers
  (let [dir (e2e/copy-project "pending")
        deps (pr-str {:deps {'volley/volley {:local/root (str (fs/absolutize "."))}}})
        {:keys [exit out]} (e2e/sh dir ["clojure" "-Sdeps" deps "-M" "-m" "volley.main"
                                        "--runtime" "bb" "--print-result"]
                                   {:timeout-ms 180000})]
    (is (= 0 exit))
    (is (map? (e2e/print-result out)))))

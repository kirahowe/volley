(ns volley.e2e.jvm-aliases-test
  "volley with JVM workers from a babashka parent: other deps.edn aliases,
  and a classpath that cannot be computed."
  (:require
   [volley.e2e.support :as e2e]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private expected-summary "4 tests | 4 passed | 0 failed | 0 errors | 0 skipped")

(defn- summary [out]
  (some-> (e2e/summary-line out) (str/replace #" \(.*\)$" "")))

(deftest bb-parent-picks-other-aliases
  (testing "--aliases picks other aliases; a missing :test alias would lose the helper"
    (let [dir (e2e/copy-project "jvm" {"deps.edn" (str "{:paths [\"src\"] :aliases {:other {:extra-paths [\"extra\"]"
                                                     " :jvm-opts [\"-Dvolley.fixture=other\"]}}}")})
          {:keys [exit out]} (e2e/volley dir ["--runtime" "jvm" "--aliases" ":other"]
                                        {:env {"EXPECT_FIXTURE_PROP" "other"} :timeout-ms 180000})]
      (is (= 0 exit) out)
      (is (= expected-summary (summary out))))))

(deftest bb-parent-reports-an-unusable-classpath
  (let [dir (e2e/copy-project "jvm" {"deps.edn" "{:deps {no.such/lib {:mvn/version \"0.0.0-none\"}}}"})
        {:keys [exit err]} (e2e/volley dir ["--runtime" "jvm"] {:timeout-ms 180000})]
    (is (= 250 exit))
    (is (str/includes? err "clojure -Spath"))))

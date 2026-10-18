(ns brisk.e2e.counts-test
  "What a run counts, in the summary, `--print-result` and the junit-xml
  report, compared with what Kaocha counts."
  (:require
   [babashka.fs :as fs]
   [brisk.e2e.support :as e2e]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- summary [out]
  (str/replace (str (e2e/summary-line out)) #" \(.*\)$" ""))

(deftest a-var-without-assertions-fails-like-kaocha
  ;; kaocha.type.var/-run reports :kaocha.type.var/zero-assertions, a fail type
  (let [dir (e2e/copy-project "zero-assertions")
        {:keys [exit out]} (e2e/brisk dir ["--plugin" "kaocha.plugin/junit-xml" "--junit-xml-file" "j.xml"])
        xml (slurp (str (fs/path dir "j.xml")))]
    (is (= 2 exit))
    (is (str/includes? (summary out) "| 2 passed | 2 failed | 0 errors"))
    (is (str/includes? out " FAIL  za.plain-test/no-assertion  test/za/plain_test.clj:5\n   Test ran without assertions. Did you forget an (is ...)?\n"))
    (testing "pending and filtered vars, and a var whose :each fixture asserts, do not fail"
      (is (not (str/includes? out "FAIL  za.plain-test/pending")))
      (is (not (str/includes? out "FAIL  za.plain-test/skipped")))
      (is (not (str/includes? out "FAIL  za.fixture-test"))))
    (testing "junit-xml"
      (is (str/includes? xml "failures=\"2\""))
      (is (str/includes? xml "<failure message=\"Test ran without assertions. Did you forget an (is ...)?\"")))))

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
    (is (= "5 tests | 2 passed | 2 failed | 0 errors | 1 skipped" (summary out))
        "Kaocha: 5 tests, 4 assertions, 1 pending, 2 failures")
    (is (str/includes? out " FAIL  za.plain-test/no-assertion  test/za/plain_test.clj:5\n   Test ran without assertions. Did you forget an (is ...)?\n"))
    (testing "pending and filtered vars, and a var whose :each fixture asserts, do not fail"
      (is (not (str/includes? out "FAIL  za.plain-test/pending")))
      (is (not (str/includes? out "FAIL  za.plain-test/skipped")))
      (is (not (str/includes? out "FAIL  za.fixture-test"))))
    (testing "junit-xml"
      (is (str/includes? xml "failures=\"2\""))
      (is (str/includes? xml "<failure message=\"Test ran without assertions. Did you forget an (is ...)?\"")))))

(deftest filtered-vars-are-not-part-of-the-run
  ;; kaocha.testable/run-testable gives a skipped testable no count
  (testing "--focus a var: Kaocha prints 1 tests"
    (let [dir (e2e/copy-project "filters")
          {:keys [exit out]} (e2e/brisk dir ["--focus" "filters.core-test/plain" "--plugin" "kaocha.plugin/junit-xml"
                                             "--junit-xml-file" "j.xml"])
          xml (slurp (str (fs/path dir "j.xml")))]
      (is (= 0 exit))
      (is (= "1 test | 1 passed | 0 failed | 0 errors | 0 skipped" (summary out)))
      (is (str/includes? out " ✓ filters.core-test (1 test)"))
      (is (str/includes? xml "tests=\"1\""))
      (is (= 1 (count (re-seq #"<testcase " xml))))
      (is (not (str/includes? xml "<skipped/>")))))
  (testing ":skip, :skip-meta and ^:kaocha/skip: Kaocha prints 6 tests"
    (let [{:keys [out]} (e2e/brisk (e2e/copy-project "filters") [])]
      (is (= "6 tests | 6 passed | 0 failed | 0 errors | 0 skipped" (summary out))))))

(deftest a-focus-that-matches-nothing-warns-like-kaocha
  ;; kaocha.api/run: "All N tests were skipped. Check for misspelled settings ..."
  (let [{:keys [exit err]} (e2e/brisk (e2e/copy-project "filters") ["--focus" "filters.core-test/nope"])]
    (is (= 0 exit))
    (is (re-find #"WARNING: All \d+ tests were skipped\. Check for misspelled settings" err))))

(ns brisk.e2e.cli-test
  "brisk as a real subprocess on the fixture projects in
  dev-resources/projects. Flags, config errors and exit codes."
  (:require
   [brisk.e2e.support :as e2e]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest config-and-cli-errors-use-kaocha-exit-codes
  (testing "kaocha.config/replace-by-default: a string :test-paths exits 250"
    (let [{:keys [exit err]} (e2e/run nil [] {"tests.edn" "#kaocha/v1 {:tests [{:test-paths \"test\"}]}"})]
      (is (= 250 exit))
      (is (str/includes? err ":kaocha/test-paths"))))
  (testing "brisk's own keys with unusable values exit 250 with a message"
    (let [{:keys [exit err]} (e2e/run "default" [] {"tests.edn" "#kaocha/v1 {:workers \"4\" :runtime :jvmx}"})]
      (is (= 250 exit))
      (is (str/includes? err ":workers must be a positive integer"))
      (is (str/includes? err ":runtime must be :bb or :jvm"))))
  (testing "an unreadable tests.edn exits 250"
    (is (= 250 (:exit (e2e/run nil [] {"tests.edn" "{:tests ["})))))
  (testing "kaocha.runner/run: an unknown flag prints usage and exits -1"
    (let [{:keys [exit out]} (e2e/run "default" ["--no-such-flag"])]
      (is (= 255 exit))
      (is (str/includes? out "Unknown option: \"--no-such-flag\""))
      (is (str/includes? out "USAGE:")))))

(deftest no-tests-found-is-a-warning
  (let [{:keys [exit err]} (e2e/run nil [] {"tests.edn" "#kaocha/v1 {}" "src/x/core.clj" "(ns x.core)"})]
    (is (= 0 exit))
    (is (str/includes? err "No tests were found"))))

(deftest informational-flags
  (is (= 0 (:exit (e2e/run "default" ["--help"]))))
  (is (str/starts-with? (:out (e2e/run "default" ["--version"])) "brisk "))
  (testing "--print-config: Kaocha's config shape, brisk's own settings under :brisk/*"
    (let [{:keys [exit out]} (e2e/run "suites" ["--print-config"])
          cfg (edn/read-string out)]
      (is (= 0 exit))
      (is (= [:unit :integration] (map :kaocha.testable/id (:kaocha/tests cfg))))
      (is (pos-int? (:brisk/workers cfg)))))
  (testing "--print-test-plan: kaocha.api/test-plan's shape, suites > namespaces > vars"
    (let [{:keys [exit out]} (e2e/run "suites" ["--print-test-plan" "unit"])
          plan (edn/read-string out)
          nses (mapcat :kaocha.test-plan/tests (:kaocha.test-plan/tests plan))]
      (is (= 0 exit))
      (is (= [:unit] (map :kaocha.testable/id (:kaocha.test-plan/tests plan))))
      (is (= '[app.parse-test] (map :kaocha.ns/name nses)))
      (is (= [:app.parse-test/parses-ints :app.parse-test/parses-nil]
             (map :kaocha.testable/id (mapcat :kaocha.test-plan/tests nses))))))
  (testing "--print-result: Kaocha's result shape with brisk's result under :brisk/result"
    (let [{:keys [out]} (e2e/run "suites" ["--print-result" "unit"])
          res (e2e/last-edn-line out "{:kaocha")]
      (is (= 2 (count (mapcat :kaocha.result/tests (mapcat :kaocha.result/tests (:kaocha.result/tests res))))))
      (is (= 0 (get-in res [:brisk/result :exit-code]))))))

(deftest help-groups-plugin-options
  (let [{:keys [out]} (e2e/run "default" ["--help"])
        plugins (str/index-of out "Options of Kaocha plugins")
        own (str/index-of out "brisk's own options:")]
    (is (< (str/index-of out "--focus-meta") plugins (str/index-of out "--junit-xml-file") own
           (str/index-of out "--workers")))))

(deftest invalid-configs-stop-the-run
  (testing "kaocha.runner: a config that fails Kaocha's spec exits 252"
    (let [{:keys [exit err]} (e2e/run "default" ["--config-file" "bad.edn"] {"bad.edn" "#kaocha/v1 {:tests [{:id \"unit\"}]}"})]
      (is (= 252 exit))
      (is (str/includes? err "Invalid configuration file: suite id \"unit\""))))
  (testing "values brisk cannot use exit 250"
    (let [{:keys [exit err]} (e2e/run "default" ["--config-file" "bad.edn"] {"bad.edn" "#kaocha/v1 {:preload? \"yes\"}"})]
      (is (= 250 exit))
      (is (str/includes? err ":preload? must be true or false"))))
  (testing "--watch with --print-result prints one result and exits"
    (let [{:keys [exit out]} (e2e/run "default" ["--watch" "--print-result"])]
      (is (= 1 exit))
      (is (some? (e2e/print-result out))))))

(deftest the-rerun-hint-keeps-the-config-file
  (let [{:keys [out]} (e2e/run "default" ["--config-file" "other.edn"] {"other.edn" "#kaocha/v1 {}"})]
    (is (re-find #"(?m)^  .* --config-file other\.edn --focus acme\.strings-test/wrong" out))))

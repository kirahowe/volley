(ns brisk.e2e.selection-test
  "brisk as a real subprocess on the fixture projects in
  dev-resources/projects. Which tests run: suites, filters, pending
  tests, serial namespaces and remembered timings."
  (:require
   [babashka.fs :as fs]
   [brisk.e2e.support :as e2e]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest default-config-without-tests-edn
  (let [{:keys [exit out err]} (e2e/run "default" [])]
    (is (= 1 exit))
    (is (= "4 tests | 3 passed | 1 failed | 0 errors | 0 skipped" (str/replace (e2e/summary-line out) #" \(.*\)$" "")))
    (is (str/includes? err "Did not load a configuration file"))
    (is (str/includes? out " ✓ acme.math-test (2 tests)"))
    (is (str/includes? out " FAIL  acme.strings-test/wrong  test/acme/strings_test.clj:6"))
    (is (not (str/includes? out "acme.helper")) "only -test$ namespaces are tests")
    (is (not (str/includes? out "\u001b")) "no colour when stdout is not a terminal")))

(deftest suites-and-suite-selection
  (testing "every suite"
    (let [[exit res] (e2e/result "suites" [])]
      (is (= 0 exit))
      (is (= '#{app.parse-test/parses-ints app.parse-test/parses-nil app.db-itest/round-trip}
             (set (keys (e2e/executed res)))))))
  (testing "one suite by id, with or without the colon"
    (is (= '#{app.parse-test/parses-ints app.parse-test/parses-nil}
           (set (keys (e2e/executed (second (e2e/result "suites" ["unit"])))))))
    (is (= '#{app.db-itest/round-trip}
           (set (keys (e2e/executed (second (e2e/result "suites" [":integration"]))))))))
  (testing "kaocha.runner/run: an unknown suite exits -2"
    (let [{:keys [exit out]} (e2e/run "suites" ["nope"])]
      (is (= 254 exit))
      (is (str/includes? out "No such suite: :nope, valid options: :integration, :unit.")))))

(deftest a-suite-marked-skipped-does-not-run
  (let [[exit res] (e2e/result "skipped-suite" [])]
    (is (= 0 exit))
    (is (= '#{app.parse-test/parses-ints app.parse-test/parses-nil} (set (keys (e2e/executed res)))))))

(deftest focus-skip-and-meta-filters
  (let [ran (fn [& args] (set (keys (e2e/executed (second (e2e/result "filters" args))))))]
    (testing "tests.edn :skip-meta, :skip and the default :kaocha/skip"
      (is (= '#{filters.core-test/plain filters.core-test/in-progress filters.core-test/sometimes
                filters.other-test/other-plain filters.other-test/other-wip
                filters.meta-ns-test/whole-ns-is-wip}
             (ran))))
    (testing "--focus a var"
      (is (= '#{filters.core-test/plain} (ran "--focus" "filters.core-test/plain"))))
    (testing "--focus a namespace"
      (is (= '#{filters.other-test/other-plain filters.other-test/other-wip}
             (ran "--focus" "filters.other-test"))))
    (testing "--focus-meta matches var and namespace metadata"
      (is (= '#{filters.core-test/in-progress filters.other-test/other-wip
                filters.meta-ns-test/whole-ns-is-wip}
             (ran "--focus-meta" ":wip"))))
    (testing "--skip-meta adds to the suite's"
      (is (not (contains? (ran "--skip-meta" "flaky") 'filters.core-test/sometimes))))
    (testing "--skip a namespace"
      (is (not-any? #(= "filters.other-test" (namespace %)) (ran "--skip" "filters.other-test"))))))

(deftest pending-tests-are-skipped
  (let [{:keys [exit out]} (e2e/run "pending" [])
        [_ res] (e2e/result "pending" [])]
    (is (= 0 exit))
    (is (str/starts-with? (e2e/summary-line out) "3 tests | 1 passed | 0 failed | 0 errors | 2 skipped"))
    (is (= {:status :skipped :reason :pending :pass 0 :fail 0 :error 0}
           (get-in res [:vars 'later.whole-ns-test/not-yet])))))

(deftest filters-reach-tests-the-parser-cannot-see
  (testing "--focus on a macro-defined var"
    (let [[exit res] (e2e/result "generated" ["--focus" "g.gen-test/generated"])]
      (is (= 0 exit))
      (is (= '{g.gen-test/generated :passed} (e2e/executed res)))))
  (testing "--focus-meta carried only by macro-defined vars"
    (let [{:keys [exit out err]} (e2e/run "generated" ["--focus-meta" ":integration"])]
      (is (= 0 exit))
      (is (str/includes? (e2e/summary-line out) "| 2 passed | 0 failed"))
      (is (not (str/includes? err "No tests found with metadata key")))
      (is (not (str/includes? out "g.plain-test")) "a namespace with nothing selected is not listed")))
  (testing "a focus-meta key no test carries is ignored with Kaocha's warning, once"
    (let [{:keys [exit out err]} (e2e/run "generated" ["--focus-meta" ":nobody" "--workers" "3"])]
      (is (= 1 exit))
      (is (str/starts-with? (e2e/summary-line out) "6 tests | 5 passed | 1 failed"))
      (is (= 1 (count (re-seq #"No tests found with metadata key :nobody" err))))))
  (testing "namespaces the focus does not reach are not listed"
    (let [{:keys [out]} (e2e/run "generated" ["--focus" "g.plain-test" "--focus" "g.gen-test"])]
      (is (not (str/includes? out "g.only-gen-test"))))))

(deftest synchronized-namespaces-run
  (let [{:keys [exit out]} (e2e/run "serial" ["--workers" "4"])]
    (is (= 0 exit))
    (is (str/starts-with? (e2e/summary-line out) "4 tests | 4 passed"))))

(deftest timings-are-remembered
  (let [dir (e2e/copy-project "serial")]
    (e2e/brisk dir [])
    (let [timings (edn/read-string (slurp (str (fs/path dir ".brisk/timings.edn"))))]
      (is (= '#{ordered.a-test ordered.b-test ordered.shared-state-test ordered.var-serial-test}
             (set (keys timings))))
      (is (every? nat-int? (vals timings))))))

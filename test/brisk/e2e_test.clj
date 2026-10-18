(ns brisk.e2e-test
  "brisk as a real subprocess on the fixture projects in
  dev-resources/projects, checking exit codes and the CI output."
  (:require
   [babashka.fs :as fs]
   [brisk.e2e.support :as e2e]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- run
  ([project args] (run project args {}))
  ([project args files] (e2e/brisk (e2e/copy-project project files) args)))

(defn- result
  "`--print-result` of a run: `[exit result-map]`."
  [project args]
  (let [{:keys [exit out]} (run project (conj (vec args) "--print-result"))]
    [exit (e2e/last-edn-line out "{:vars")]))

(defn- executed
  "Vars that ran, with their status."
  [res]
  (into {} (keep (fn [[v {:keys [status]}]] (when-not (= :skipped status) [v status]))) (:vars res)))

(deftest default-config-without-tests-edn
  (let [{:keys [exit out err]} (run "default" [])]
    (is (= 1 exit))
    (is (= "4 tests | 3 passed | 1 failed | 0 errors | 0 skipped" (str/replace (e2e/summary-line out) #" \(.*\)$" "")))
    (is (str/includes? err "Did not load a configuration file"))
    (is (str/includes? out " ✓ acme.math-test (2 tests)"))
    (is (str/includes? out " FAIL  acme.strings-test/wrong  test/acme/strings_test.clj:6"))
    (is (not (str/includes? out "acme.helper")) "only -test$ namespaces are tests")
    (is (not (str/includes? out "\u001b")) "no colour when stdout is not a terminal")))

(deftest suites-and-suite-selection
  (testing "every suite"
    (let [[exit res] (result "suites" [])]
      (is (= 0 exit))
      (is (= '#{app.parse-test/parses-ints app.parse-test/parses-nil app.db-itest/round-trip}
             (set (keys (executed res)))))))
  (testing "one suite by id, with or without the colon"
    (is (= '#{app.parse-test/parses-ints app.parse-test/parses-nil}
           (set (keys (executed (second (result "suites" ["unit"])))))))
    (is (= '#{app.db-itest/round-trip}
           (set (keys (executed (second (result "suites" [":integration"]))))))))
  (testing "kaocha.runner/run: an unknown suite exits -2"
    (let [{:keys [exit out]} (run "suites" ["nope"])]
      (is (= 254 exit))
      (is (str/includes? out "No such suite: :nope, valid options: :integration, :unit.")))))

(deftest focus-skip-and-meta-filters
  (let [ran (fn [& args] (set (keys (executed (second (result "filters" args))))))]
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
  (let [{:keys [exit out]} (run "pending" [])
        [_ res] (result "pending" [])]
    (is (= 0 exit))
    (is (str/starts-with? (e2e/summary-line out) "3 tests | 1 passed | 0 failed | 0 errors | 2 skipped"))
    (is (= {:status :skipped :reason :pending :pass 0 :fail 0 :error 0}
           (get-in res [:vars 'later.whole-ns-test/not-yet])))))

(deftest failing-and-erroring-tests
  (let [{:keys [exit out]} (run "failures" [])]
    (testing "kaocha.runner: exit code is fail + error assertions"
      (is (= 4 exit)))
    (is (str/starts-with? (e2e/summary-line out) "8 tests | 4 passed | 2 failed | 2 errors | 0 skipped"))
    (is (str/includes? out "diff (- expected, + actual):"))
    (is (str/includes? out "testing: adding to an empty cart"))
    (is (str/includes? out "clojure.lang.ExceptionInfo: inventory unavailable"))
    (is (str/includes? out "data: {:sku 42}"))
    (is (str/includes? out "| computing the discount") "captured output under the failing test")))

(deftest load-errors-are-reported-per-namespace
  (let [{:keys [exit out]} (run "load-errors" [])]
    (is (= 3 exit))
    (is (str/includes? out "ERROR  shop.missing-dep-test  failed to load"))
    (is (str/includes? out "ERROR  shop.fixture-error-test  failed in a fixture"))
    (is (str/includes? out "database not reachable"))
    (is (str/includes? out "ERROR  ns form could not be read  test/shop/broken_syntax_test.clj"))
    (is (str/includes? out " ✓ shop.passing-test (2 tests)") "other namespaces still run")))

(deftest a-crashing-worker-is-one-failure
  (let [{:keys [exit out]} (run "crash" [])]
    (is (= 1 exit))
    (is (str/includes? out "CRASH  boom.exit-test/exits-the-process  worker exited with code 3"))
    (is (str/includes? out "| about to exit"))
    (is (str/includes? out " ✓ boom.survivor-test (1 test)"))
    (is (str/starts-with? (e2e/summary-line out) "3 tests | 2 passed | 0 failed | 1 errors"))))

(deftest a-worker-that-exits-0-mid-namespace-is-a-crash
  (let [{:keys [exit out]} (run "exit-zero" ["--workers" "1"])]
    (is (= 1 exit) "System/exit 0 in a test must not pass the run")
    (is (str/includes? out "CRASH  ez.exits-test/exits-cleanly  worker exited with code 0"))
    (is (str/includes? out " ✓ ez.survivor-test (1 test)"))))

(deftest synchronized-namespaces-run
  (let [{:keys [exit out]} (run "serial" ["--workers" "4"])]
    (is (= 0 exit))
    (is (str/starts-with? (e2e/summary-line out) "4 tests | 4 passed"))))

(deftest fail-fast-stops-and-lists-what-did-not-run
  (let [{:keys [exit out]} (run "fail-fast" ["--workers" "1"])]
    (is (= 1 exit))
    (is (str/includes? out "Not run (stopped by fail-fast):\n  ff.n1-test, ff.n2-test, ff.n3-test, ff.n4-test"))
    (is (not (str/includes? out "ff.n1-test (")) "nothing after the failure ran")))

(deftest fail-fast-kills-the-other-workers-at-once
  (let [t0 (System/currentTimeMillis)
        {:keys [exit out]} (run "fail-fast-parallel" ["--workers" "3"])
        [_ res] (result "fail-fast-parallel" ["--workers" "3"])
        elapsed (- (System/currentTimeMillis) t0)]
    (is (= 1 exit) "the failure's exit code")
    (is (< elapsed 40000) "the minute-long tests were not waited for (two runs)")
    (is (str/includes? out "Interrupted (stopped by fail-fast):\n  ffp.slow-1-test, ffp.slow-2-test"))
    (is (str/includes? out "Not run (stopped by fail-fast):\n  ffp.z-queued-test"))
    (is (re-find #"(?m)^ - ffp.slow-1-test \(interrupted by fail-fast, 1 done\)" out))
    (is (str/starts-with? (e2e/summary-line out) "3 tests | 2 passed | 1 failed | 0 errors")
        "interrupted namespaces count what finished, and are not failures")
    (testing "the failing worker stops after the failing var"
      (is (= :failed (get-in res [:vars 'ffp.a-fails-test/first-failure :status])))
      (is (not (contains? (:vars res) 'ffp.a-fails-test/second-is-never-run))))
    (is (= '{ffp.slow-1-test :fail-fast ffp.slow-2-test :fail-fast} (:interrupted res)))
    (is (= :interrupted (get-in res [:namespaces 'ffp.slow-1-test])))))

(deftest config-and-cli-errors-use-kaocha-exit-codes
  (testing "kaocha.config/replace-by-default: a string :test-paths exits 250"
    (let [{:keys [exit err]} (run nil [] {"tests.edn" "#kaocha/v1 {:tests [{:test-paths \"test\"}]}"})]
      (is (= 250 exit))
      (is (str/includes? err ":kaocha/test-paths"))))
  (testing "an unreadable tests.edn exits 250"
    (is (= 250 (:exit (run nil [] {"tests.edn" "{:tests ["})))))
  (testing "kaocha.runner/run: an unknown flag prints usage and exits -1"
    (let [{:keys [exit out]} (run "default" ["--no-such-flag"])]
      (is (= 255 exit))
      (is (str/includes? out "Unknown option: \"--no-such-flag\""))
      (is (str/includes? out "USAGE:")))))

(deftest no-tests-found-is-a-warning
  (let [{:keys [exit err]} (run nil [] {"tests.edn" "#kaocha/v1 {}" "src/x/core.clj" "(ns x.core)"})]
    (is (= 0 exit))
    (is (str/includes? err "No tests were found"))))

(deftest informational-flags
  (is (= 0 (:exit (run "default" ["--help"]))))
  (is (str/starts-with? (:out (run "default" ["--version"])) "brisk "))
  (let [{:keys [exit out]} (run "suites" ["--print-config"])
        cfg (edn/read-string out)]
    (is (= 0 exit))
    (is (= [:unit :integration] (map :id (:tests cfg)))))
  (let [{:keys [exit out]} (run "suites" ["--print-test-plan" "unit"])
        plan (edn/read-string out)]
    (is (= 0 exit))
    (is (= '[app.parse-test] (map :ns (mapcat :namespaces (:suites plan)))))))

(deftest colour-and-reporters
  (testing "--color forces colour, NO_COLOR wins over it"
    (let [dir (e2e/copy-project "default")]
      (is (str/includes? (:out (e2e/brisk dir ["--color"])) "\u001b["))
      (is (not (str/includes? (:out (e2e/brisk dir ["--color"] {:env {"NO_COLOR" "1"}})) "\u001b[")))))
  (testing "--reporter documentation: one line per var"
    (let [{:keys [out]} (run "default" ["--reporter" "documentation"])]
      (is (str/includes? out "acme.math-test"))
      (is (re-find #"(?m)^  ✓ adds \d+ms$" out))
      (is (re-find #"(?m)^  ✗ wrong \d+ms$" out))))
  (testing "--reporter dots maps to the CI output and warns"
    (let [{:keys [out err]} (run "default" ["--reporter" "dots"])]
      (is (str/includes? err "kaocha.report/dots"))
      (is (some? (e2e/summary-line out))))))

(deftest timings-are-remembered
  (let [dir (e2e/copy-project "serial")]
    (e2e/brisk dir [])
    (let [timings (edn/read-string (slurp (str (fs/path dir ".brisk/timings.edn"))))]
      (is (= '#{ordered.a-test ordered.b-test ordered.shared-state-test ordered.var-serial-test}
             (set (keys timings))))
      (is (every? nat-int? (vals timings))))))

(deftest print-vars-a-test-binds-cannot-hide-failures
  (testing "a fixture, a test and a top-level set! changing *print-length* and friends"
    (let [{:keys [exit out]} (e2e/brisk (e2e/copy-project "print-vars") ["--workers" "3"]
                                        {:timeout-ms 60000})]
      (is (= 6 exit))
      (is (str/starts-with? (e2e/summary-line out) "7 tests | 1 passed | 5 failed | 1 errors"))
      (is (str/includes? out "actual:   [1 2 4]") "the value printed in full")
      (is (not (str/includes? out "could not be read"))))))

(deftest tests-that-read-stdin-see-an-empty-stream
  (doseq [runtime ["bb" "jvm"]]
    (testing runtime
      (let [{:keys [exit out]} (e2e/brisk (e2e/copy-project "stdin") ["--workers" "1" "--runtime" runtime]
                                          {:timeout-ms 60000})]
        (is (= 0 exit) out)
        (is (str/starts-with? (str (e2e/summary-line out)) "3 tests | 3 passed"))))))

(deftest background-processes-do-not-delay-the-run
  (testing "a test's daemon holds the worker's pipes; exits are still seen at once"
    (let [t0 (System/currentTimeMillis)
          {:keys [exit out]} (e2e/brisk (e2e/copy-project "subprocess") ["--workers" "2"]
                                        {:timeout-ms 60000})
          elapsed (- (System/currentTimeMillis) t0)]
      (is (= 1 exit))
      (is (str/includes? out "CRASH  sp.crash-test/crashes-with-a-daemon  worker exited with code 1"))
      (is (str/includes? out " ✓ sp.daemon-test (1 test)"))
      (is (< elapsed 8000) "neither the crash nor the shutdown waited for the 20s daemons"))))

(deftest filters-reach-tests-the-parser-cannot-see
  (testing "--focus on a macro-defined var"
    (let [[exit res] (result "generated" ["--focus" "g.gen-test/generated"])]
      (is (= 0 exit))
      (is (= '{g.gen-test/generated :passed} (executed res)))))
  (testing "--focus-meta carried only by macro-defined vars"
    (let [{:keys [exit out err]} (run "generated" ["--focus-meta" ":integration"])]
      (is (= 0 exit))
      (is (str/includes? (e2e/summary-line out) "| 2 passed | 0 failed"))
      (is (not (str/includes? err "No tests found with metadata key")))
      (is (not (str/includes? out "g.plain-test")) "a namespace with nothing selected is not listed")))
  (testing "a focus-meta key no test carries is ignored with Kaocha's warning, once"
    (let [{:keys [exit out err]} (run "generated" ["--focus-meta" ":nobody" "--workers" "3"])]
      (is (= 1 exit))
      (is (str/starts-with? (e2e/summary-line out) "6 tests | 5 passed | 1 failed"))
      (is (= 1 (count (re-seq #"No tests found with metadata key :nobody" err))))))
  (testing "namespaces the focus does not reach are not listed"
    (let [{:keys [out]} (run "generated" ["--focus" "g.plain-test" "--focus" "g.gen-test"])]
      (is (not (str/includes? out "g.only-gen-test"))))))

(deftest junit-xml-reports-like-kaocha-junit-xml
  (testing "--plugin and --junit-xml-file on the command line"
    (let [dir (e2e/copy-project "default")
          {:keys [exit]} (e2e/brisk dir ["--plugin" "kaocha.plugin/junit-xml" "--junit-xml-file" "target/junit.xml"])
          xml (slurp (str (fs/path dir "target/junit.xml")))]
      (is (= 1 exit))
      (is (str/includes? xml "<testsuite name=\"unit\""))
      (is (str/includes? xml "<testcase name=\"acme.strings-test/wrong\" classname=\"acme.strings-test\""))
      (is (str/includes? xml "<failure message=\"[:fail] expected:"))))
  (testing "the plugin and :kaocha.plugin.junit-xml/target-file in tests.edn"
    (let [dir (e2e/copy-project "default" {"tests.edn" (str "#kaocha/v1 {:plugins [:kaocha.plugin/junit-xml]"
                                                            " :kaocha.plugin.junit-xml/target-file \"out/j.xml\"}")})
          {:keys [exit err]} (e2e/brisk dir [])]
      (is (= 1 exit))
      (is (not (str/includes? err "junit")) "no warning about the plugin")
      (is (fs/exists? (fs/path dir "out/j.xml")))))
  (testing "a plugin's flag without the plugin is an unknown option, as in Kaocha"
    (let [{:keys [exit out]} (run "default" ["--junit-xml-file" "j.xml"])]
      (is (= 255 exit))
      (is (str/includes? out "Unknown option: \"--junit-xml-file\""))))
  (testing "flags of a listed plugin brisk does not run are accepted with a warning"
    (let [{:keys [exit err]} (run "default" ["--plugin" "cloverage" "--cov-output" "cov" "--no-cov-html"])]
      (is (= 1 exit))
      (is (str/includes? err "--cov-output ignored: :kaocha.plugin/cloverage is not supported")))))

(deftest assertions-in-fixtures-count-like-kaocha
  (let [[exit res] (result "fixture-assertions" [])
        {:keys [out]} (run "fixture-assertions" [])]
    (is (= 3 exit) "one :once fixture failure, one :each failure per test")
    (testing "kaocha.type.var: an :each fixture's assertions count for the test it wraps"
      (is (= {:status :failed :pass 1 :fail 1 :error 0} (dissoc (get-in res [:vars 'fa.each-test/a]) :reason))))
    (testing "a :once fixture's assertions count for the namespace, not a phantom test"
      (is (= #{'fa.each-test/a 'fa.each-test/b 'fa.once-test/a} (set (keys (:vars res)))))
      (is (= :failed (get-in res [:namespaces 'fa.once-test]))))
    (is (str/starts-with? (e2e/summary-line out) "3 tests | 1 passed | 2 failed | 0 errors | 0 skipped (7 assertions"))
    (is (str/includes? out "FAIL  fa.once-test  in a :once fixture"))))

(deftest a-throwing-each-fixture-lists-the-tests-it-kept-from-running
  (let [[exit res] (result "each-fixture-throws" [])
        {:keys [out]} (run "each-fixture-throws" [])]
    (is (= 1 exit))
    (is (= {'et.a-test/a :passed 'et.a-test/b :passed} (executed res)))
    (is (= {:status :skipped :reason :fixture-failed :pass 0 :fail 0 :error 0}
           (get-in res [:vars 'et.a-test/c])))
    (is (str/includes? out "not run: c, d"))))

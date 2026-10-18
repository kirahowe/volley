(ns volley.e2e.failures-test
  "volley as a real subprocess on the fixture projects in
  dev-resources/projects. How failures, errors, load errors, crashes and
  fixture problems are counted and shown."
  (:require
   [volley.e2e.support :as e2e]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest failing-and-erroring-tests
  (let [{:keys [exit out]} (e2e/run "failures" [])]
    (testing "kaocha.runner: exit code is fail + error assertions"
      (is (= 4 exit)))
    (is (str/starts-with? (e2e/summary-line out) "8 tests | 4 passed | 2 failed | 2 errors | 0 skipped"))
    (is (str/includes? out "diff (- expected, + actual):"))
    (is (str/includes? out "testing: adding to an empty cart"))
    (is (str/includes? out "clojure.lang.ExceptionInfo: inventory unavailable"))
    (is (str/includes? out "data: {:sku 42}"))
    (is (str/includes? out "| computing the discount") "captured output under the failing test")))

(deftest load-errors-are-reported-per-namespace
  (let [{:keys [exit out]} (e2e/run "load-errors" [])]
    (is (= 3 exit))
    (is (str/includes? out "ERROR  shop.missing-dep-test  failed to load"))
    (is (str/includes? out "ERROR  shop.fixture-error-test  failed in a fixture"))
    (is (str/includes? out "database not reachable"))
    (is (str/includes? out "ERROR  ns form could not be read  test/shop/broken_syntax_test.clj"))
    (is (str/includes? out " ✓ shop.passing-test (2 tests)") "other namespaces still run")))

(deftest a-crashing-worker-is-one-failure
  (let [{:keys [exit out]} (e2e/run "crash" [])]
    (is (= 1 exit))
    (is (str/includes? out "CRASH  boom.exit-test/exits-the-process  worker exited with code 3"))
    (is (str/includes? out "| about to exit"))
    (is (str/includes? out " ✓ boom.survivor-test (1 test)"))
    (is (str/starts-with? (e2e/summary-line out) "3 tests | 2 passed | 0 failed | 1 error |"))))

(deftest a-worker-that-exits-0-mid-namespace-is-a-crash
  (let [{:keys [exit out]} (e2e/run "exit-zero" ["--workers" "1"])]
    (is (= 1 exit) "System/exit 0 in a test must not pass the run")
    (is (str/includes? out "CRASH  ez.exits-test/exits-cleanly  worker exited with code 0"))
    (is (str/includes? out " ✓ ez.survivor-test (1 test)"))))

(deftest a-throwing-each-fixture-lists-the-tests-it-kept-from-running
  (let [[exit res] (e2e/result "each-fixture-throws" [])
        {:keys [out]} (e2e/run "each-fixture-throws" [])]
    (is (= 1 exit))
    (is (= {'et.a-test/a :passed 'et.a-test/b :passed} (e2e/executed res)))
    (is (= {:status :skipped :reason :fixture-failed :pass 0 :fail 0 :error 0}
           (get-in res [:vars 'et.a-test/c])))
    (is (str/includes? out "not run: c, d"))))

(deftest assertions-in-fixtures-count-like-kaocha
  (let [[exit res] (e2e/result "fixture-assertions" [])
        {:keys [out]} (e2e/run "fixture-assertions" [])]
    (is (= 3 exit) "one :once fixture failure, one :each failure per test")
    (testing "kaocha.type.var: an :each fixture's assertions count for the test it wraps"
      (is (= {:status :failed :pass 1 :fail 1 :error 0} (dissoc (get-in res [:vars 'fa.each-test/a]) :reason))))
    (testing "a :once fixture's assertions count for the namespace, not a phantom test"
      (is (= #{'fa.each-test/a 'fa.each-test/b 'fa.once-test/a} (set (keys (:vars res)))))
      (is (= :failed (get-in res [:namespaces 'fa.once-test]))))
    (is (str/starts-with? (e2e/summary-line out) "3 tests | 1 passed | 2 failed | 0 errors | 0 skipped (7 assertions"))
    (is (str/includes? out "FAIL  fa.once-test  in a :once fixture"))))

(deftest print-vars-a-test-binds-cannot-hide-failures
  (testing "a fixture, a test and a top-level set! changing *print-length* and friends"
    (let [{:keys [exit out]} (e2e/volley (e2e/copy-project "print-vars") ["--workers" "3"]
                                        {:timeout-ms 60000})]
      (is (= 6 exit))
      (is (str/starts-with? (e2e/summary-line out) "7 tests | 1 passed | 5 failed | 1 error |"))
      (is (str/includes? out "actual:   [1 2 4]") "the value printed in full")
      (is (not (str/includes? out "could not be read"))))))

(deftest kaocha-bindings-reach-the-tests
  (let [[exit res] (e2e/result "bindings" [])]
    (is (= 0 exit))
    (is (= '{bd.a-test/sees-the-binding :passed} (e2e/executed res))))
  (testing "a var that does not exist stops the namespace, as Kaocha fails the run"
    (let [{:keys [exit out]} (e2e/run "bindings" [] {"tests.edn" "#kaocha/v1 {:bindings {bd.config/*nope* 1}}"})]
      (is (= 1 exit))
      (is (str/includes? out ":kaocha/bindings: no such var bd.config/*nope*")))))

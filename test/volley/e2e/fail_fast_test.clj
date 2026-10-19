(ns volley.e2e.fail-fast-test
  "volley as a real subprocess on the fixture projects in
  dev-resources/projects. `--fail-fast`: what stops, what is killed and
  what is listed."
  (:require
   [volley.e2e.support :as e2e]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest fail-fast-stops-and-lists-what-did-not-run
  (let [{:keys [exit out]} (e2e/run "fail-fast" ["--workers" "1" "--no-randomize"])]
    (is (= 1 exit))
    (is (str/includes? out "Not run (stopped by fail-fast):\n  ff.n1-test, ff.n2-test, ff.n3-test, ff.n4-test"))
    (is (not (str/includes? out "ff.n1-test (")) "nothing after the failure ran")))

(deftest fail-fast-kills-the-other-workers-at-once
  (let [t0 (System/currentTimeMillis)
        ;; the CI output and the result come from two runs, side by side
        result (future (e2e/result "fail-fast-parallel" ["--workers" "3" "--no-randomize"]))
        {:keys [exit out]} (e2e/run "fail-fast-parallel" ["--workers" "3" "--no-randomize"])
        [_ res] @result
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

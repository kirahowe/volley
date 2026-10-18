(ns brisk.e2e.isolation-test
  "brisk as a real subprocess on the fixture projects in
  dev-resources/projects. What a test can do to its process without
  disturbing the run: read stdin, start background processes."
  (:require
   [brisk.e2e.support :as e2e]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

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

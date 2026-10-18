(ns brisk.e2e.hook-errors-test
  "Hooks that throw or misbehave, counted and named as Kaocha would have
  them (dev-resources/projects/hook-errors)."
  (:require
   [brisk.e2e.support :as e2e]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn- run [config & args]
  (e2e/brisk (e2e/copy-project "hook-errors") (into ["--config-file" config] args)))

(deftest a-throwing-post-run-counts-once
  ;; Kaocha runs post-run once; brisk runs it in every worker
  (let [{:keys [exit out]} (run "post-run.edn" "--workers" "3")]
    (is (= 1 exit) out)
    (is (= 1 (count (re-seq #"the post-run hook he.hooks/throw-post-run failed" out))))))

(deftest a-throwing-pre-test-hook-is-named
  (let [{:keys [exit out]} (run "pre-test.edn" "--workers" "1")]
    (is (= 3 exit))
    (is (str/includes? out " ✗ he.a-test (pre-test hook failed)"))
    (is (str/includes? out " ERROR  he.a-test  the pre-test hook he.hooks/throw-pre-test failed"))
    (is (not (str/includes? out "fixture")))))

(deftest a-wrap-run-that-never-runs-is-warned-about
  ;; Kaocha prints 0 tests and exits 0; brisk does too, with a warning
  (let [{:keys [exit out err]} (run "wrap-run.edn" "--workers" "1")]
    (is (= 0 exit))
    (is (str/starts-with? (e2e/summary-line out) "0 tests"))
    (is (str/includes? err "WARNING: the wrap-run hook did not call run for he.a-test, so its 1 test did not run"))))

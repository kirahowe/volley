(ns brisk.e2e.preload-test
  "`--preload`: each worker loads every planned namespace first, as Kaocha
  does, suite by suite."
  (:require
   [brisk.e2e.support :as e2e]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest preload-sets-up-each-suite-before-loading-it
  (testing "suite :b's worker setup runs before pb.b-test loads, whichever suite the worker runs first"
    (let [{:keys [exit out err]} (e2e/brisk (e2e/copy-project "preload-suites") ["--preload" "--workers" "1"])]
      (is (= 0 exit) (str out err))
      (is (= "2 tests | 2 passed | 0 failed | 0 errors | 0 skipped"
             (str/replace (str (e2e/summary-line out)) #" \(.*\)$" ""))))))

(deftest preload-load-errors-are-reported
  (let [{:keys [exit err]} (e2e/brisk (e2e/copy-project "load-errors") ["--preload" "--workers" "2"])]
    (is (pos? exit))
    (is (= 1 (count (re-seq #"--preload could not load shop.missing-dep-test" err)))
        "once per run, however many workers preload it")))

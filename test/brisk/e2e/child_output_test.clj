(ns brisk.e2e.child-output-test
  "Child processes of a test inherit the worker's stdout, the protocol
  pipe, and write to it whatever and whenever they like."
  (:require
   [brisk.e2e.support :as e2e]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(deftest child-output-cannot-hide-or-forge-events
  (let [{:keys [exit out err]} (e2e/brisk (e2e/copy-project "child-output") ["--workers" "1"] {:timeout-ms 30000})]
    (is (= 0 exit) (str out err))
    (is (= "3 tests | 3 passed | 0 failed | 0 errors | 0 skipped"
           (str/replace (str (e2e/summary-line out)) #" \(.*\)$" "")))
    (is (str/includes? out "stopping db...") "the partial line is still shown")
    (is (str/includes? out "#brisk/event {:oops") "the fake event is plain output")
    (is (not (str/includes? (str out err) "protocol")))))

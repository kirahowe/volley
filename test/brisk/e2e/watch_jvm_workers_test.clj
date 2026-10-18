(ns brisk.e2e.watch-jvm-workers-test
  "`brisk --watch` as a real subprocess. Watch mode with JVM workers
  started from a babashka parent."
  (:require
   [brisk.e2e.support :as e2e]
   [brisk.e2e.watch-support :as ws]
   [clojure.test :refer [deftest is]]))

(deftest watch-with-jvm-workers-from-a-bb-parent
  (let [dir (e2e/copy-project "jvm")
        w (ws/start! dir (ws/brisk-command "--watch" "--runtime" "jvm") {"EXPECT_FIXTURE_PROP" "alias"})]
    (try
      (ws/jvm-watch-session dir w)
      (finally
        (is (not= ::e2e/timeout (ws/stop! w)))))))

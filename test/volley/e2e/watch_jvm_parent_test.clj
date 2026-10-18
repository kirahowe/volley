(ns volley.e2e.watch-jvm-parent-test
  "`volley --watch` as a real subprocess. Watch mode with the parent on
  the JVM."
  (:require
   [babashka.fs :as fs]
   [volley.e2e.support :as e2e]
   [volley.e2e.watch-support :as ws]
   [clojure.test :refer [deftest is]]))

(deftest watch-with-a-jvm-parent
  (let [dir (e2e/copy-project "jvm")
        deps (pr-str {:paths ["src" "extra"]
                      :deps {'volley/volley {:local/root (str (fs/absolutize "."))}}})
        w (ws/start! dir ["clojure" "-Sdeps" deps "-J-Dvolley.fixture=parent" "-M" "-m" "volley.main" "--watch"]
                  {"EXPECT_FIXTURE_PROP" "parent"})]
    (try
      (ws/jvm-watch-session dir w)
      (finally
        (is (not= ::e2e/timeout (ws/stop! w)))))))

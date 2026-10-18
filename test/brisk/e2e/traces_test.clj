(ns brisk.e2e.traces-test
  "Error traces name the user's frames, on babashka and JVM workers."
  (:require
   [brisk.e2e.support :as e2e]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest error-traces-name-the-user-frames-on-both-runtimes
  ;; deep.core/outer calls middle calls innermost, which divides by zero;
  ;; relay-explosion calls explode, which throws ex-info.
  (doseq [runtime ["bb" "jvm"]]
    (testing runtime
      (let [dir (e2e/copy-project "traces")
            {:keys [exit out err]} (e2e/brisk dir ["--runtime" runtime] {:timeout-ms 180000})
            frame-line (fn [fn-name] (re-find (re-pattern (str "(?m)^ +at deep\\.core.?" fn-name ".*core\\.clj:\\d+")) out))]
        (is (= 2 exit) (str out err))
        (doseq [f ["innermost" "middle" "outer" "explode" "relay.explosion"]]
          (is (frame-line f) (str "a trace line for " f "\n" out)))
        (is (re-find #"core\.clj:4\)" out) "innermost's line, where the division is")
        (is (str/includes? out "data: {:id 7, :via :relay}") "the error the test raised, not a wrapper")))))

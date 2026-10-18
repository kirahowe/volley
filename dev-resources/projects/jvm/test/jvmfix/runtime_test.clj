(ns jvmfix.runtime-test
  (:require
   [clojure.test :refer [deftest is]]
   [jvmfix.helper :as helper]))

(deftest runs-on-the-jvm
  (is (nil? (System/getProperty "babashka.version"))))

(deftest alias-path-is-on-the-classpath
  (is (= :helper (helper/id))))

(deftest jvm-option-reached-the-worker
  (is (= (System/getenv "EXPECT_FIXTURE_PROP")
         (System/getProperty "brisk.fixture"))))

(deftest heap-option-reached-the-worker
  (when-let [mb (System/getenv "EXPECT_MAX_HEAP_MB")]
    (let [max-mb (quot (.maxMemory (Runtime/getRuntime)) 1048576)]
      (is (<= (- (Long/parseLong mb) 64) max-mb (+ (Long/parseLong mb) 64))))))

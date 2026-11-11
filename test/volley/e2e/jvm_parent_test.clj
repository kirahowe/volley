(ns volley.e2e.jvm-parent-test
  "volley with its parent on the JVM, started by the Clojure CLI, end to
  end on the fixture projects."
  (:require
   [babashka.fs :as fs]
   [volley.e2e.support :as e2e]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private expected-summary "4 tests | 4 passed | 0 failed | 0 errors | 0 skipped")

(defn- summary [out]
  (some-> (e2e/summary-line out) (str/replace #" \(.*\)$" "")))

(deftest jvm-parent-starts-jvm-workers-with-its-options
  (let [dir (e2e/copy-project "jvm")
        deps (pr-str {:paths ["src" "extra"]
                      :deps {'volley/volley {:local/root (str (fs/absolutize "."))}}})
        {:keys [exit out err]}
        (e2e/sh dir ["clojure" "-Sdeps" deps "-J-Dvolley.fixture=parent" "-J-Xmx384m"
                     "-M" "-m" "volley.main"]
                {:env {"EXPECT_FIXTURE_PROP" "parent" "EXPECT_MAX_HEAP_MB" "384"}
                 :timeout-ms 180000})]
    (is (= 0 exit) (str out err))
    (is (= expected-summary (summary out)))))

(deftest jvm-parent-can-still-start-bb-workers
  (let [dir (e2e/copy-project "pending")
        deps (pr-str {:deps {'volley/volley {:local/root (str (fs/absolutize "."))}}})
        {:keys [exit out]} (e2e/sh dir ["clojure" "-Sdeps" deps "-M" "-m" "volley.main"
                                        "--runtime" "bb" "--print-result"]
                                   {:timeout-ms 180000})]
    (is (= 0 exit))
    (is (map? (e2e/print-result out)))))

(deftest a-short-jvm-run-goes-in-process-next-time
  ;; volley#2: once a short run is on record, the next one runs on a
  ;; thread of the parent JVM instead of starting a worker JVM
  (let [dir (e2e/copy-project "jvm")
        run (fn [paths & args]
              (e2e/sh dir (into ["clojure" "-Sdeps" (pr-str {:paths paths
                                                             :deps {'volley/volley {:local/root (str (fs/absolutize "."))}}})
                                 "-J-Dvolley.fixture=parent" "-M" "-m" "volley.main"]
                                args)
                      {:env {"EXPECT_FIXTURE_PROP" "parent"} :timeout-ms 180000}))
        history #(edn/read-string (slurp (str (fs/path dir ".volley" "history.edn"))))
        passes? (fn [{:keys [exit out err]}]
                  (is (= 0 exit) (str out err))
                  (is (= expected-summary (summary out))))]
    (passes? (run ["src" "extra"]))
    (is (nil? (:in-process-ms (history))) "no history: worker processes")
    (passes? (run ["src" "extra"]))
    (is (= 1 (count (:in-process-ms (history))))
        "a short run on record: in process, though the parent's classpath lacks the test path")
    (passes? (run ["src" "extra" "test"]))
    (is (= 2 (count (:in-process-ms (history)))) "and with the test path on it")
    (testing "namespace infos are reused only for files whose text is unchanged"
      (is (fs/exists? (fs/path dir ".volley" "ns-infos.edn")))
      (spit (str (fs/path dir "test" "jvmfix" "runtime_test.clj"))
            "\n(deftest added-later (is true))\n" :append true)
      (let [{:keys [exit out]} (run ["src" "extra" "test"] "--print-test-plan")]
        (is (= 0 exit))
        (is (str/includes? out "jvmfix.runtime-test/added-later"))))))

(deftest an-in-process-run-loads-from-the-suites-test-paths
  ;; a JVM worker's classpath adds every suite's test paths, and a run on
  ;; a thread of the parent adds them as kaocha.classpath/add-classpath
  ;; does: here a test path inside a classpath root, and one holding a
  ;; resource and a helper namespace
  (let [dir (e2e/copy-project nil {"tests.edn" (pr-str {:tests [{:id :unit :test-paths ["test/tp" "test-resources"]}]})
                                   "test-resources/fixture.txt" "here\n"
                                   "test-resources/tph/helper.clj" "(ns tph.helper)\n(defn id [] :helper)\n"
                                   "test/tp/resource_test.clj"
                                   (str "(ns tp.resource-test\n"
                                        "  (:require [clojure.java.io :as io] [clojure.test :refer [deftest is]] [tph.helper :as helper]))\n"
                                        "(deftest reads-fixture (is (some? (io/resource \"fixture.txt\"))))\n"
                                        "(deftest loads-helper (is (= :helper (helper/id))))\n"
                                        "(deftest context-loader-reads-fixture\n"
                                        "  (is (some? (.getResource (.getContextClassLoader (Thread/currentThread)) \"fixture.txt\"))))\n"
                                        "(deftest a-new-thread-reads-fixture\n"
                                        "  (let [found (promise)]\n"
                                        "    (.start (Thread. (fn [] (deliver found (some? (io/resource \"fixture.txt\"))))))\n"
                                        "    (is (true? (deref found 5000 :timeout)))))\n")})
        run #(e2e/sh dir ["clojure" "-Sdeps" (pr-str {:paths ["test"]
                                                      :deps {'volley/volley {:local/root (str (fs/absolutize "."))}}})
                          "-M" "-m" "volley.main"]
                     {:timeout-ms 180000})
        history #(edn/read-string (slurp (str (fs/path dir ".volley" "history.edn"))))]
    (dotimes [_ 2]
      (let [{:keys [exit out err]} (run)]
        (is (= 0 exit) (str out err))
        (is (= expected-summary (summary out)))))
    (is (= 1 (count (:workers-ms (history)))) "no history: worker processes")
    (is (= 1 (count (:in-process-ms (history)))) "then in process, with the same tests passing")))

(deftest system-exit-in-an-in-process-run-fails-the-run
  ;; in a worker process the call crashes that worker; on a thread of the
  ;; parent it ends the JVM, which must not exit 0 without a word
  (let [dir (e2e/copy-project nil {"test/ex/fine_test.clj"
                                   "(ns ex.fine-test (:require [clojure.test :refer [deftest is]]))\n(deftest fine (is true))\n"})
        run #(e2e/sh dir ["clojure" "-Sdeps" (pr-str {:paths ["test"]
                                                      :deps {'volley/volley {:local/root (str (fs/absolutize "."))}}})
                          "-M" "-m" "volley.main"]
                     {:timeout-ms 180000})]
    (is (= 0 (:exit (run))) "a short run with worker processes, for the history")
    (spit (str (fs/path dir "test" "ex" "exit_test.clj"))
          "(ns ex.exit-test (:require [clojure.test :refer [deftest is]]))\n(deftest exits-zero (System/exit 0) (is true))\n")
    (let [{:keys [exit out err]} (run)]
      (is (= 1 exit) (str out err))
      (is (str/includes? err "System/exit was called while the tests ran in volley's own JVM"))
      (is (re-find #"called from ex\.exit_test\S* \(exit_test\.clj:2\)" err)))))

(ns volley.e2e.watch-support
  "Driving `volley --watch` as a real subprocess: start it, wait for runs
  to finish on its output, change files."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [volley.e2e.support :as e2e]
   [clojure.string :as str]
   [clojure.test :refer [is testing]])
  (:import
   (java.io BufferedReader InputStreamReader)))

(def marker
  "What watch mode prints when a run has finished."
  "Waiting for file changes")

(defn volley-command
  "`bb -m volley.main args` with volley's source and the project's `src`."
  [& args]
  (into ["bb" "-cp" (e2e/classpath e2e/volley-src "src") "-m" "volley.main"] args))

(defn start!
  "Start `cmd` in `dir`. Returns `{:proc p :out atom-of-string}`; the atom
  grows as the process prints (stdout and stderr merged)."
  ([dir cmd] (start! dir cmd {}))
  ([dir cmd env]
  (let [proc (p/process cmd {:dir dir :in :pipe :out :pipe :err :out
                             :extra-env (merge {"NO_COLOR" ""} env)})
        out (atom "")]
    (future
      (with-open [rdr (BufferedReader. (InputStreamReader. (:out proc) "UTF-8"))]
        (loop []
          (when-let [line (.readLine rdr)]
            (swap! out str line "\n")
            (recur)))))
    {:proc proc :out out})))

(defn runs-finished
  "How many runs the watch process has finished so far."
  [{:keys [out]}]
  (count (re-seq (re-pattern marker) @out)))

(defn await-run!
  "Wait until `n` runs have finished. Returns the output since run `n - 1`
  finished, or throws with everything printed so far."
  [{:keys [out] :as w} n]
  (let [deadline (+ (System/currentTimeMillis) 120000)]
    (loop []
      (cond
        (>= (runs-finished w) n)
        (let [text @out
              m (re-matcher (re-pattern (str marker ".*\n")) text)
              ends (vec (take-while some? (repeatedly #(when (.find m) (.end m)))))
              from (if (> n 1) (nth ends (- n 2)) 0)]
          (subs text from (nth ends (dec n))))

        (> (System/currentTimeMillis) deadline)
        (throw (ex-info (str "timed out waiting for run " n "\n" @out) {:out @out}))

        :else (do (Thread/sleep 50) (recur))))))

(defn ran
  "Test namespaces mentioned by finished-namespace lines in `text`."
  [text]
  (set (map second (re-seq #"(?m)^ [✓✗] (\S+) " text))))

(defn stop!
  "Kill a process `start!` started; its result, or ::e2e/timeout."
  [{:keys [proc]}]
  (p/destroy-tree proc)
  (deref proc 10000 ::e2e/timeout))

(defn write!
  "Write `text` to `path` under `dir`."
  [dir path text]
  (spit (str (fs/path dir path)) text)
  ;; Never let two writes share a millisecond timestamp.
  (Thread/sleep 5))

(def more-test
  "A second test namespace for the `jvm` fixture."
  "(ns jvmfix.more-test\n  (:require [clojure.test :refer [deftest is]]))\n\n(deftest more-runs (is true))\n")

(defn jvm-watch-session
  "Drive a watch process on a copy of the `jvm` fixture: the first run, a
  test change, and a tests.edn change that adds a test path, which only
  works if the worker classpath is rebuilt."
  [dir w]
  (testing "the initial run"
    (let [text (await-run! w 1)]
      (is (= #{"jvmfix.runtime-test"} (ran text)) text)
      (is (str/includes? text "PASS  Waiting for file changes"))))
  (testing "a changed test reruns in fresh JVM workers"
    (write! dir "test/jvmfix/runtime_test.clj"
            (str (slurp (str (fs/path dir "test/jvmfix/runtime_test.clj")))
                 "\n(deftest added-later (is (= 2 (inc 1))))\n"))
    (let [text (await-run! w 2)]
      (is (str/includes? text " ✓ jvmfix.runtime-test (5 tests)") text)))
  (testing "a new test path in tests.edn reaches the workers' classpath"
    (fs/create-dirs (fs/path dir "more/jvmfix"))
    (spit (str (fs/path dir "more/jvmfix/more_test.clj")) more-test)
    (write! dir "tests.edn" "#kaocha/v1 {:tests [{:id :unit :test-paths [\"test\" \"more\"]}]}\n")
    (let [text (await-run! w 3)]
      (is (= #{"jvmfix.runtime-test" "jvmfix.more-test"} (ran text)) text)
      (is (str/includes? text "PASS  Waiting for file changes")))))

(defn in-pty
  "`script` runs `sh-cmd` with a pseudo-terminal as its controlling
  terminal; nil when it is not available."
  [sh-cmd]
  (let [mac? (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")]
    (when (fs/which "script")
      (if mac?
        ["script" "-q" "/dev/null" "sh" "-c" sh-cmd]
        ["script" "-qec" (str "sh -c '" (str/replace sh-cmd "'" "'\\''") "'") "/dev/null"]))))

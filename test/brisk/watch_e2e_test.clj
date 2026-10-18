(ns brisk.watch-e2e-test
  "`brisk --watch` as a real subprocess on a copy of the `watch` fixture
  project. Every wait is on the process's output with a timeout."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [brisk.e2e.support :as e2e]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.io BufferedReader InputStreamReader)))

(def ^:private marker "Waiting for file changes")

(defn- brisk-command [& args]
  (into ["bb" "-cp" (e2e/classpath e2e/brisk-src "src") "-m" "brisk.main"] args))

(defn- start!
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

(defn- runs-finished [{:keys [out]}]
  (count (re-seq (re-pattern marker) @out)))

(defn- await-run!
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

(defn- ran
  "Test namespaces mentioned by finished-namespace lines in `text`."
  [text]
  (set (map second (re-seq #"(?m)^ [✓✗] (\S+) " text))))

(defn- stop! [{:keys [proc]}]
  (p/destroy-tree proc)
  (deref proc 10000 ::timeout))

(defn- write! [dir path text]
  (spit (str (fs/path dir path)) text)
  ;; Never let two writes share a millisecond timestamp.
  (Thread/sleep 5))

(deftest watch-reruns-only-what-a-change-affects
  (let [dir (e2e/copy-project "watch")
        w (start! dir (brisk-command "--watch"))]
    (try
      (testing "the initial run covers everything"
        (let [text (await-run! w 1)]
          (is (= #{"wx.alpha-test" "wx.beta-test" "wx.plain-test"} (ran text)))
          (is (str/includes? text "PASS  Waiting for file changes"))
          (is (not (str/includes? text "press h")) "no key hint without a terminal")))
      (testing "a source change reruns only its dependents"
        (write! dir "src/wx/alpha.clj" "(ns wx.alpha)\n\n(defn double-it [x] (+ x x))\n")
        (let [text (await-run! w 2)]
          (is (= #{"wx.alpha-test"} (ran text)))
          (is (str/includes? text "Change detected: src/wx/alpha.clj"))
          (is (str/includes? text "PASS"))))
      (testing "a broken test shows FAIL"
        (write! dir "test/wx/alpha_test.clj"
                (str "(ns wx.alpha-test\n  (:require [clojure.test :refer [deftest is]]\n"
                     "            [wx.alpha :as alpha]))\n\n"
                     "(deftest doubles (is (= 5 (alpha/double-it 2))))\n"))
        (let [text (await-run! w 3)]
          (is (= #{"wx.alpha-test"} (ran text)))
          (is (str/includes? text "FAIL  1 failure  Waiting for file changes"))
          (is (str/includes? text "expected: 5"))))
      (testing "fixing it shows PASS again"
        (write! dir "test/wx/alpha_test.clj"
                (str "(ns wx.alpha-test\n  (:require [clojure.test :refer [deftest is]]\n"
                     "            [wx.alpha :as alpha]))\n\n"
                     "(deftest doubles (is (= 4 (alpha/double-it 2))))\n"))
        (let [text (await-run! w 4)]
          (is (= #{"wx.alpha-test"} (ran text)))
          (is (str/includes? text "PASS  Waiting for file changes"))))
      (testing "a new test file runs by itself"
        (write! dir "test/wx/gamma_test.clj"
                (str "(ns wx.gamma-test\n  (:require [clojure.test :refer [deftest is]]\n"
                     "            [wx.beta :as beta]))\n\n"
                     "(deftest quiet (is (= \"a!\" (beta/shout \"a\"))))\n"))
        (is (= #{"wx.gamma-test"} (ran (await-run! w 5)))))
      (testing "a change to a shared source reruns both its test namespaces"
        (write! dir "src/wx/beta.clj" "(ns wx.beta)\n\n(defn shout [s] (str s \"!\"))\n;; edited\n")
        (is (= #{"wx.beta-test" "wx.gamma-test"} (ran (await-run! w 6)))))
      (testing "a deleted test file is handled without a crash"
        (fs/delete (fs/path dir "test/wx/gamma_test.clj"))
        (let [text (await-run! w 7)]
          (is (str/includes? text "No test namespaces matched"))
          (is (str/includes? text "PASS"))))
      (testing "a tests.edn change reruns everything"
        (write! dir "tests.edn" "#kaocha/v1 {:color? false}\n")
        (is (= #{"wx.alpha-test" "wx.beta-test" "wx.plain-test"} (ran (await-run! w 8)))))
      (finally
        (is (not= ::timeout (stop! w)) "the process ends when killed")))))

(deftest watch-quits-on-q-in-a-terminal
  (if (and (= "Mac OS X" (System/getProperty "os.name")) (fs/which "script"))
    (let [dir (e2e/copy-project "watch")
          w (start! dir (into ["script" "-q" "/dev/null"] (brisk-command "--watch")))]
      (try
        (await-run! w 1)
        (is (str/includes? @(:out w) "press h to show help, q to quit"))
        (.write (:in (:proc w)) (int \q))
        (.flush (:in (:proc w)))
        (let [res (deref (:proc w) 20000 ::timeout)]
          (is (not= ::timeout res) "q ends the process")
          (is (= 0 (:exit res))))
        (finally
          (when (.isAlive ^Process (:proc (:proc w))) (stop! w)))))
    (is true "needs macOS script(1)")))

(def ^:private more-test
  "(ns jvmfix.more-test\n  (:require [clojure.test :refer [deftest is]]))\n\n(deftest more-runs (is true))\n")

(defn- jvm-watch-session
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

(deftest watch-with-jvm-workers-from-a-bb-parent
  (let [dir (e2e/copy-project "jvm")
        w (start! dir (brisk-command "--watch" "--runtime" "jvm") {"EXPECT_FIXTURE_PROP" "alias"})]
    (try
      (jvm-watch-session dir w)
      (finally
        (is (not= ::timeout (stop! w)))))))

(deftest watch-with-a-jvm-parent
  (let [dir (e2e/copy-project "jvm")
        deps (pr-str {:paths ["src" "extra"]
                      :deps {'brisk/brisk {:local/root (str (fs/absolutize "."))}}})
        w (start! dir ["clojure" "-Sdeps" deps "-J-Dbrisk.fixture=parent" "-M" "-m" "brisk.main" "--watch"]
                  {"EXPECT_FIXTURE_PROP" "parent"})]
    (try
      (jvm-watch-session dir w)
      (finally
        (is (not= ::timeout (stop! w)))))))

(defn- in-pty
  "`script` runs `sh-cmd` with a pseudo-terminal as its controlling
  terminal; nil when it is not available."
  [sh-cmd]
  (let [mac? (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")]
    (when (fs/which "script")
      (if mac?
        ["script" "-q" "/dev/null" "sh" "-c" sh-cmd]
        ["script" "-qec" (str "sh -c '" (str/replace sh-cmd "'" "'\\''") "'") "/dev/null"]))))

(deftest terminal-settings-are-restored-when-watch-mode-is-killed
  (testing "the shutdown hook restores icanon and echo (babashka.process refuses to run there)"
    (let [dir (e2e/copy-project "default")
          cmd (str (str/join " " (map #(str "'" % "'") (brisk-command "--watch")))
                   " < /dev/tty > out.txt 2>&1 & pid=$!;"
                   " n=0; until grep -q '" marker "' out.txt || [ $n -gt 300 ]; do sleep 0.1; n=$((n+1)); done;"
                   " kill -TERM $pid; wait $pid; stty -a")]
      (when-let [argv (in-pty cmd)]
        (let [{:keys [out]} (e2e/sh dir argv {:timeout-ms 60000})]
          (is (str/includes? (slurp (str (fs/path dir "out.txt"))) marker) "watch mode was running")
          (is (re-find #"(?<![-\w])icanon" out) "canonical mode is back")
          (is (re-find #"(?<![-\w])echo\b" out) "echo is back"))))))

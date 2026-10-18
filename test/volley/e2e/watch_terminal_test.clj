(ns volley.e2e.watch-terminal-test
  "`volley --watch` as a real subprocess. Watch mode on a terminal: keys,
  and the terminal settings it restores."
  (:require
   [babashka.fs :as fs]
   [volley.e2e.support :as e2e]
   [volley.e2e.watch-support :as ws]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest watch-quits-on-q-in-a-terminal
  (if (and (= "Mac OS X" (System/getProperty "os.name")) (fs/which "script"))
    (let [dir (e2e/copy-project "watch")
          w (ws/start! dir (into ["script" "-q" "/dev/null"] (ws/volley-command "--watch")))]
      (try
        (ws/await-run! w 1)
        (is (str/includes? @(:out w) "press h to show help, q to quit"))
        (.write (:in (:proc w)) (int \q))
        (.flush (:in (:proc w)))
        (let [res (deref (:proc w) 20000 ::e2e/timeout)]
          (is (not= ::e2e/timeout res) "q ends the process")
          (is (= 0 (:exit res))))
        (finally
          (when (.isAlive ^Process (:proc (:proc w))) (ws/stop! w)))))
    (is true "needs macOS script(1)")))

(deftest terminal-settings-are-restored-when-watch-mode-is-killed
  (testing "the shutdown hook restores icanon and echo (babashka.process refuses to run there)"
    (let [dir (e2e/copy-project "default")
          cmd (str (str/join " " (map #(str "'" % "'") (ws/volley-command "--watch")))
                   " < /dev/tty > out.txt 2>&1 & pid=$!;"
                   " n=0; until grep -q '" ws/marker "' out.txt || [ $n -gt 300 ]; do sleep 0.1; n=$((n+1)); done;"
                   " kill -TERM $pid; wait $pid; stty -a")]
      (when-let [argv (ws/in-pty cmd)]
        (let [{:keys [out]} (e2e/sh dir argv {:timeout-ms 60000})]
          (is (str/includes? (slurp (str (fs/path dir "out.txt"))) ws/marker) "watch mode was running")
          (is (re-find #"(?<![-\w])icanon" out) "canonical mode is back")
          (is (re-find #"(?<![-\w])echo\b" out) "echo is back"))))))

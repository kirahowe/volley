(ns volley.e2e.graceful-stop-test
  "Workers stopped early, by fail-fast or by a watch-mode cancel, are asked
  to shut down first, so their `post-run` hooks run. The fixture's hook
  writes a marker file per worker with the vars it ran."
  (:require
   [babashka.fs :as fs]
   [volley.e2e.support :as e2e]
   [volley.e2e.watch-support :as ws]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- post-run-markers
  "The `{ns-sym n}` maps the workers' post-run hooks wrote under `dir`."
  [dir]
  (let [markers (fs/path dir "markers")]
    (if (fs/exists? markers)
      (mapv #(edn/read-string (slurp (str %))) (fs/glob markers "post-run-*.edn"))
      [])))

(defn- await-file [path ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (fs/exists? path) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 20) (recur))))))

(defn- stopped-early?
  "True for a marker of a worker that ran some but not all 20 steps."
  [m]
  (< 0 (get m 'gs.steps-test 0) 20))

(deftest fail-fast-lets-the-interrupted-workers-run-their-post-run-hooks
  (let [dir (e2e/copy-project "graceful-stop")
        t0 (System/currentTimeMillis)
        {:keys [exit out]} (e2e/volley dir ["--fail-fast" "--workers" "3"])
        elapsed (- (System/currentTimeMillis) t0)
        markers (post-run-markers dir)]
    (is (= 1 exit) out)
    (is (str/includes? out "Interrupted (stopped by fail-fast):\n  gs.hung-test, gs.steps-test"))
    (testing "the failing worker and the one that stopped in time ran post-run"
      (is (some #(contains? % 'gs.a-fails-test) markers) (pr-str markers))
      (is (some stopped-early? markers) (pr-str markers)))
    (testing "the test that never returns was killed after the grace period"
      (is (not-any? #(contains? % 'gs.hung-test) markers))
      (is (< elapsed 30000) "the ten-minute test was not waited for"))))

(deftest a-watch-cancel-lets-the-workers-run-their-post-run-hooks
  (let [dir (e2e/copy-project "graceful-stop")
        w (ws/start! dir (ws/volley-command "--watch" "--skip" "gs.hung-test" "--skip" "gs.a-fails-test"))]
    (try
      (is (await-file (fs/path dir "markers" "started-gs.steps-test") 60000) "the run started")
      (Thread/sleep 300)
      (ws/write! dir "test/gs/steps_test.clj"
                 (str (slurp (str (fs/path dir "test/gs/steps_test.clj"))) ";; edited\n"))
      (let [deadline (+ (System/currentTimeMillis) 20000)]
        (loop []
          (when (and (not-any? stopped-early? (post-run-markers dir))
                     (< (System/currentTimeMillis) deadline))
            (Thread/sleep 50)
            (recur))))
      (is (some stopped-early? (post-run-markers dir))
          (str "the cancelled run's worker ran post-run\n" @(:out w)))
      (finally
        (is (not= ::e2e/timeout (ws/stop! w)))))))

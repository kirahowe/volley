(ns brisk.hooks-e2e-test
  "Kaocha's hooks plugin and :brisk/worker-setup end to end, on babashka
  and JVM workers. The `hooks` fixture's hooks append `[pid hook detail]`
  to hooks.log, which shows which process ran each hook and in what order."
  (:require
   [babashka.fs :as fs]
   [brisk.e2e.support :as e2e]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn- hook-log [dir]
  (mapv edn/read-string (str/split-lines (slurp (str (fs/path dir "hooks.log"))))))

(defn- index-of [entries pred]
  (first (keep-indexed (fn [i e] (when (pred e) i)) entries)))

(defn- check-worker
  "The order of one worker's hooks."
  [entries]
  (let [hooks (map second entries)
        once (fn [h] (= 1 (count (filter #{h} hooks))))
        at (fn [h detail] (index-of entries #(= [h detail] (rest %))))
        nses (keep (fn [[_ h d]] (when (= :wrap-run h) d)) entries)]
    (is (= [:worker-setup :pre-load :suite-setup] (take 3 hooks))
        "worker setup first, before anything is loaded")
    (doseq [h [:worker-setup :pre-load :pre-run :post-run :suite-setup :suite-before :suite-after]]
      (is (once h) (str h " once per worker")))
    (is (< (index-of entries #(= :suite-after (second %)))
           (index-of entries #(= :post-run (second %))))
        "the suite's post-test, then post-run, at shutdown")
    (doseq [n nses]
      (let [order [(at :pre-load-test n) (index-of entries #(and (= :post-load-test (second %)) (= n (first (nth % 2)))))
                   (at :pre-test n) (at :wrap-run n)
                   (index-of entries #(and (= :post-test (second %)) (= n (first (nth % 2)))))]]
        (is (every? some? order) (str n " " order))
        (is (apply < order) (str "load and run hooks of " n " in order"))))
    nses))

(defn- check-run [dir out exit bb?]
  (let [log (hook-log dir)
        by-pid (group-by first log)
        workers (into {} (filter (fn [[_ es]] (some #(= :worker-setup (second %)) es))) by-pid)
        parent (remove (set (keys workers)) (keys by-pid))]
    (is (= 0 exit) out)
    ;; A worker sets up before its first namespace, so one that never got
    ;; a namespace (it started late on a busy machine) has nothing logged.
    (is (<= 1 (count workers) 2))
    (is (every? (fn [[_ h d]] (or (not= :worker-setup h) (= bb? (some? (:bb d))))) log)
        "the workers ran on the requested runtime")
    (is (= #{:hooks.a-test :hooks.b-test :hooks.c-test}
           (set (mapcat #(check-worker (val %)) workers)))
        "every namespace ran in a worker that was set up")
    (is (= 1 (count parent)))
    (is (= [[:post-summary 4]] (map rest (get by-pid (first parent))))
        "post-summary once, in the parent, with Kaocha's result shape")
    (is (str/starts-with? (e2e/summary-line out) "4 tests | 4 passed | 0 failed | 0 errors | 0 skipped")
        "pre-report turned the defused failure into a pass; pre-test skipped a var, which, as in Kaocha, is not counted")))

(deftest kaocha-hooks-and-worker-setup-on-bb-workers
  (let [dir (e2e/copy-project "hooks")
        {:keys [exit out err]} (e2e/brisk dir ["--workers" "2"])]
    (check-run dir (str out err) exit true)))

(deftest kaocha-hooks-and-worker-setup-on-jvm-workers
  (let [dir (e2e/copy-project "hooks")
        {:keys [exit out err]} (e2e/brisk dir ["--workers" "2" "--runtime" "jvm"] {:timeout-ms 180000})]
    (check-run dir (str out err) exit false)))

(deftest a-failing-worker-setup-runs-no-tests
  (let [dir (e2e/copy-project "hooks" {"tests.edn" "#kaocha/v1 {:brisk/worker-setup [hooks.probe/no-such-fn]}"})
        {:keys [exit out]} (e2e/brisk dir ["--workers" "1"])]
    (is (= 3 exit) "each namespace is one error")
    (is (str/includes? out "ERROR  hooks.a-test  not run: worker setup failed"))
    (is (str/includes? out "hook hooks.probe/no-such-fn does not exist"))
    (is (str/starts-with? (e2e/summary-line out) "0 tests"))))

(deftest hooks-without-the-plugin-warn
  (let [dir (e2e/copy-project "hooks" {"tests.edn" "#kaocha/v1 {:kaocha.hooks/pre-load [hooks.probe/pre-load]}"})
        {:keys [err]} (e2e/brisk dir [])]
    (is (str/includes? err "hooks :kaocha.hooks/pre-load ignored: :kaocha.plugin/hooks is not in :plugins"))
    (is (not (fs/exists? (fs/path dir "hooks.log"))))))

(deftest a-failing-shutdown-hook-is-reported
  (let [edn (str/replace (slurp "dev-resources/projects/hooks/tests.edn") "[hooks.probe/post-run]"
                         "[(fn [r] (throw (ex-info \"post-run broke\" {})))]")
        dir (e2e/copy-project "hooks" {"tests.edn" edn})
        {:keys [exit out]} (e2e/brisk dir ["--workers" "1"])]
    (is (= 1 exit) "the tests pass; the failed hook is the one failure")
    (is (str/includes? out "a hook failed as it shut down") out)
    (is (str/includes? out "post-run broke") "a list form is evaluated in the worker, as Kaocha's load-hook does")))

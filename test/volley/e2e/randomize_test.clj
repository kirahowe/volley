(ns volley.e2e.randomize-test
  "volley as a real subprocess: the randomize plugin's seeded var order,
  also across a tests.edn change in watch mode."
  (:require
   [volley.e2e.support :as e2e]
   [volley.e2e.watch-support :as ws]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private names (mapv #(str "t" %) (range 10)))

(def ^:private files
  {"tests.edn" "#kaocha/v1 {}"
   "test/r/order_test.clj"
   (str "(ns r.order-test (:require [clojure.test :refer [deftest is]]))\n"
        (str/join "\n" (for [n names]
                         (str "(deftest " n " (println \"ran " n "\") (is (not= \"t9\" \"" n "\")))"))))})

(defn- run-order
  "The order the vars ran in, and the output."
  [args]
  (let [{:keys [out]} (e2e/run nil (into ["--no-capture-output" "--workers" "1"] args) files)]
    [(mapv second (re-seq #"(?m)^ran (t\d+)$" out)) out]))

(deftest a-seed-reproduces-the-var-order
  (let [[a out] (run-order ["--seed" "1234"])
        [b] (run-order ["--seed" "1234"])
        [plain] (run-order ["--no-randomize"])]
    (is (= (sort names) (sort a)) "every var ran once")
    (is (= a b) "the same seed, the same order")
    (is (= names plain) "name order without randomize")
    (is (not= plain a))
    (testing "kaocha.plugin.randomize prints the seed of a failed run"
      (is (str/includes? out "Randomized with --seed 1234")))))

(deftest watch-takes-the-seed-from-a-reloaded-tests-edn
  (let [dir (e2e/copy-project nil (assoc files "tests.edn" "#kaocha/v1 {:kaocha.plugin.randomize/seed 1234}"))
        w (ws/start! dir (ws/volley-command "--watch" "--no-capture-output" "--workers" "1"))
        order (fn [text] (mapv second (re-seq #"(?m)^ran (t\d+)$" text)))]
    (try
      (let [first-run (ws/await-run! w 1)]
        (is (str/includes? first-run "Randomized with --seed 1234"))
        (testing "turning randomize off restores name order"
          (ws/write! dir "tests.edn" "#kaocha/v1 {:kaocha.plugin.randomize/randomize? false}\n")
          (let [text (ws/await-run! w 2)]
            (is (= names (order text)))
            (is (not (str/includes? text "Randomized with")))))
        (testing "a new seed takes effect"
          (ws/write! dir "tests.edn" "#kaocha/v1 {:kaocha.plugin.randomize/seed 5678}\n")
          (let [text (ws/await-run! w 3)]
            (is (= (sort names) (sort (order text))))
            (is (not= names (order text)))
            (is (str/includes? text "Randomized with --seed 5678")))))
      (finally
        (is (not= ::e2e/timeout (ws/stop! w)) "the process ends when killed")))))

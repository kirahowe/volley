(ns volley.e2e.randomize-test
  "volley as a real subprocess: the randomize plugin's seeded var order."
  (:require
   [volley.e2e.support :as e2e]
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

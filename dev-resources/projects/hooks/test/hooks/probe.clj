(ns hooks.probe
  "Hooks that write what they were called with to hooks.log, one EDN
  vector per line: [pid hook detail]."
  (:require [clojure.string :as str]))

(def setup-done (atom false))

(defn- pid [] (.pid (java.lang.ProcessHandle/current)))

(defn- log! [hook detail]
  (locking log!
    (spit "hooks.log" (str (pr-str [(pid) hook detail]) "\n") :append true)))

(defn- id [testable] (:kaocha.testable/id testable))

(defn worker-setup! [] (reset! setup-done true) (log! :worker-setup {:bb (System/getProperty "babashka.version")}))
(defn suite-setup! [] (log! :suite-setup nil))

(defn pre-load [config] (log! :pre-load (mapv :kaocha.testable/id (:kaocha/tests config))) config)
(defn post-load [plan]
  (log! :post-load (vec (for [s (:kaocha.test-plan/tests plan) n (:kaocha.test-plan/tests s)] (id n))))
  plan)
(defn pre-run [plan] (log! :pre-run nil) plan)
(defn post-run [result]
  (log! :post-run (vec (for [s (:kaocha.result/tests result) n (:kaocha.result/tests s)] (id n))))
  result)
(defn wrap-run [run]
  (fn [testable plan]
    (log! :wrap-run (id testable))
    (let [result (run testable plan)]
      (log! :wrap-run-end (id testable))
      result)))
(defn pre-load-test [testable _config] (log! :pre-load-test (id testable)) testable)
(defn post-load-test [testable _config]
  (log! :post-load-test [(id testable) (count (:kaocha.test-plan/tests testable))])
  testable)
(defn pre-test [testable _plan]
  (log! :pre-test (id testable))
  (cond-> testable
    (:skip-by-hook (:kaocha.testable/meta testable)) (assoc :kaocha.testable/skip true)))
(defn post-test [testable _plan]
  (log! :post-test [(id testable) (:kaocha.result/pass testable) (:kaocha.result/fail testable)])
  testable)
(defn suite-before [testable _plan] (log! :suite-before (id testable)) testable)
(defn suite-after [testable _plan] (log! :suite-after (id testable)) testable)
(defn pre-report [event]
  (if (and (= :fail (:type event)) (str/includes? (str (:message event)) "defuse"))
    (assoc event :type :pass)
    event))
(defn post-summary [result]
  (log! :post-summary
        (reduce + (for [s (:kaocha.result/tests result) n (:kaocha.result/tests s) v (:kaocha.result/tests n)]
                    (:kaocha.result/pass v 0))))
  result)

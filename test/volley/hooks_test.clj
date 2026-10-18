(ns volley.hooks-test
  (:require
   [volley.config :as config]
   [volley.gen.compat :as gc]
   [volley.gen.report :as gr]
   [volley.hooks :as hooks]
   [volley.run-state :as rs]
   [volley.testable :as testable]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.properties :as prop]))

(defspec configure-message-only-when-something-runs-in-workers 300
  (prop/for-all [raw gc/raw-config]
    (let [c (config/normalize raw {})
          msg (hooks/configure-message c)
          worker-side? (or (seq (dissoc (:hooks c) :post-summary))
                           (seq (:worker-setup c))
                           (seq (:reporters c))
                           (some #(or (seq (:hooks %)) (seq (:worker-setup %))) (:tests c)))]
      (and (= (boolean worker-side?) (some? msg))
           (or (nil? msg)
               (and (= :configure (:op msg))
                    (not (contains? (:hooks msg) :post-summary))
                    (= (set (map :id (:tests c))) (set (keys (:testables msg))))))))))

(defspec kaocha-result-counts-match-the-run-state 200
  (prop/for-all [state gr/state]
    (let [cfg {:tests [{:id :unit :test-paths ["test"]}]}
          result (hooks/kaocha-result cfg (constantly :unit) state)
          vars (for [s (:kaocha.result/tests result)
                     n (:kaocha.result/tests s)
                     v (:kaocha.result/tests n)]
                 v)
          ;; like Kaocha's totals, the result leaves out what :once fixtures assert
          outside (map rs/ns-counts (remove rs/unselected? (vals (:namespaces state))))
          sum (fn [k ok] (+ (reduce + (keep k vars)) (reduce + (map ok outside))))
          {:keys [assertions]} (rs/summary state)]
      (and (= (:passed assertions) (sum :kaocha.result/pass :pass))
           (= (:failed assertions) (sum :kaocha.result/fail :fail))
           (= (:errored assertions) (sum :kaocha.result/error :error))
           (= (count (mapcat (comp vals :vars) (vals (:namespaces state)))) (count vars))))))

(deftest testables-have-kaochas-shape
  (testing "kaocha.type.ns/->testable"
    (is (= {:kaocha.testable/type :kaocha.type/ns :kaocha.testable/id :my.app-test
            :kaocha.testable/desc "my.app-test" :kaocha.ns/name 'my.app-test}
           (testable/ns-testable 'my.app-test))))
  (testing "kaocha.type.ns/-load builds var testables"
    (is (= {:kaocha.testable/type :kaocha.type/var :kaocha.testable/id :my.app-test/adds
            :kaocha.testable/desc "adds" :kaocha.testable/meta {:line 3}
            :kaocha.var/name 'my.app-test/adds}
           (testable/var-testable 'my.app-test 'adds {:line 3}))))
  (is (= {:kaocha.result/count 1 :kaocha.result/pass 2 :kaocha.result/fail 1
          :kaocha.result/error 0 :kaocha.result/pending 0}
         (testable/with-result {} {:pass 2 :fail 1}))))

(deftest the-semantics-table-covers-every-supported-key
  (is (= hooks/top-level-keys (set (keys hooks/semantics))))
  (is (every? hooks/top-level-keys hooks/suite-keys))
  (is (= #{:post-summary} (set (keep (fn [[k v]] (when (= :parent (:where v)) k)) hooks/semantics)))))

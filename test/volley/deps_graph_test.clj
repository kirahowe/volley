(ns volley.deps-graph-test
  (:require
   [volley.deps-graph :as deps]
   [volley.discover :as discover]
   [volley.paths :as paths]
   [volley.gen.compat :as g]
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

(def ^:private graph-and-paths
  (gen/let [infos g/graph-infos
            picks (gen/vector gen/nat 0 4)
            more (gen/vector gen/nat 0 3)]
    (let [files (mapv :file infos)
          pick (fn [ns] (mapv #(nth files (mod % (count files))) ns))]
      {:infos infos :graph (deps/graph infos) :a (pick picks) :b (pick more)})))

(defn- reverse-requires
  "Namespaces among `infos` that directly require `ns`."
  [infos ns]
  (set (for [i infos :when (contains? (:requires i) ns)] (:ns i))))

(defspec affected-is-always-a-set-of-test-namespaces 300
  (prop/for-all [{:keys [graph a]} graph-and-paths]
    (set/subset? (deps/affected graph a) (:tests graph))))

(defspec changed-test-namespaces-affect-themselves 300
  (prop/for-all [{:keys [infos graph a]} graph-and-paths]
    (let [changed-ns (set (for [i infos :when (and (:test? i) (some #{(:file i)} a))] (:ns i)))]
      (set/subset? changed-ns (deps/affected graph a)))))

(defspec affected-is-monotonic-in-changed-paths 300
  (prop/for-all [{:keys [graph a b]} graph-and-paths]
    (set/subset? (deps/affected graph a) (deps/affected graph (concat a b)))))

(defn- oracle
  "Reverse-dependency closure by naive fixpoint over the raw infos."
  [infos changed-ns]
  (loop [reach changed-ns]
    (let [more (into reach (comp (filter #(some reach (:requires %))) (map :ns)) infos)]
      (if (= more reach) reach (recur more)))))

(defspec affected-is-the-reverse-dependency-closure-restricted-to-tests 300
  (prop/for-all [{:keys [infos graph a]} graph-and-paths]
    (let [changed-ns (into #{} (keep (:files graph)) a)
          tests (set (for [i infos :when (:test? i)] (:ns i)))]
      (= (set/intersection tests (oracle infos changed-ns))
         (deps/affected graph a)))))

(defspec affected-ignores-order-and-duplicates 200
  (prop/for-all [{:keys [graph a]} graph-and-paths]
    (= (deps/affected graph a) (deps/affected graph (reverse (concat a a))))))

(defspec unknown-paths-affect-every-test-namespace 200
  (prop/for-all [{:keys [graph a]} graph-and-paths
                 junk (gen/elements ["resources/x.edn" "README.md" "src/new_file.clj"])]
    (= (:tests graph) (deps/affected graph (cons junk a)))))

(defspec no-changes-affect-nothing 100
  (prop/for-all [{:keys [graph]} graph-and-paths]
    (empty? (deps/affected graph []))))

(defspec cyclic-graphs-terminate 200
  (prop/for-all [n (gen/choose 1 6)]
    ;; every namespace requires every other one
    (let [names (mapv #(symbol (str "n" %)) (range n))
          infos (mapv (fn [s] {:file (str s ".clj") :ns s :requires (set names) :test? true}) names)
          graph (deps/graph infos)]
      (= (set names) (deps/affected graph [(str (first names) ".clj")])))))

(defspec reverse-edges-match-requires 200
  (prop/for-all [{:keys [infos graph]} graph-and-paths]
    (every? (fn [{:keys [ns]}] (= (reverse-requires infos ns) (get-in graph [:rdeps ns])))
            infos)))

(deftest worked-example-test
  (let [infos [{:file "src/app/db.clj" :ns 'app.db :requires '#{next.jdbc}}
               {:file "src/app/core.clj" :ns 'app.core :requires '#{app.db clojure.string}}
               {:file "src/app/util.clj" :ns 'app.util :requires #{}}
               {:file "test/app/core_test.clj" :ns 'app.core-test :requires '#{app.core clojure.test} :test? true}
               {:file "test/app/util_test.clj" :ns 'app.util-test :requires '#{app.util clojure.test} :test? true}
               {:file "test/app/helpers.clj" :ns 'app.helpers :requires '#{app.db}}
               {:file "test/app/db_test.clj" :ns 'app.db-test :requires '#{app.helpers} :test? true}]
        graph (deps/graph infos)]
    (testing "a source change reaches the tests that require it, transitively"
      (is (= '#{app.core-test app.db-test} (deps/affected graph ["src/app/db.clj"])))
      (is (= '#{app.core-test} (deps/affected graph ["src/app/core.clj"])))
      (is (= '#{app.util-test} (deps/affected graph ["src/app/util.clj"]))))
    (testing "a changed test namespace affects itself, a helper reaches its users"
      (is (= '#{app.util-test} (deps/affected graph ["test/app/util_test.clj"])))
      (is (= '#{app.db-test} (deps/affected graph ["test/app/helpers.clj"]))))
    (testing "several changes union"
      (is (= '#{app.util-test app.core-test}
             (deps/affected graph ["src/app/util.clj" "src/app/core.clj"]))))
    (testing "files the graph doesn't know cannot be traced, so everything is affected"
      (is (= '#{app.core-test app.util-test app.db-test} (deps/affected graph ["resources/config.edn"])))
      (is (= '#{app.core-test app.util-test app.db-test} (deps/affected graph ["tests.edn"]))))
    (testing "libraries are not nodes"
      (is (= #{} (get-in graph [:rdeps 'app.util-test])))
      (is (not (contains? (:deps graph) 'next.jdbc))))))

(deftest built-from-discover-output-test
  (let [infos (paths/mark-tests
               [{:test-paths ["test"] :ns-patterns ["-test$"]}]
               [(discover/ns-info "src/a.clj" "(ns a)")
                (discover/ns-info "test/a_test.clj" "(ns a-test (:require [a :as a] [clojure.test]))")])
        graph (deps/graph infos)]
    (is (= '#{a-test} (deps/affected graph ["src/a.clj"])))
    (is (= '#{a-test} (:tests graph)))))

(deftest files-without-a-namespace-test
  (let [graph (deps/graph [{:file "x.clj"} {:file "test/bad_test.clj" :error "boom"}])]
    (is (= #{} (:tests graph)))
    (is (= #{} (deps/affected graph [])))))

(ns brisk.select-test
  (:require
   [brisk.config :as config]
   [brisk.gen.compat :as g]
   [brisk.select :as select]
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- info
  "A static ns info under test/ with the given ns meta and vars."
  ([ns vars] (info ns {} vars))
  ([ns meta vars]
   {:file (str "test/" (name ns) ".clj") :ns ns :requires #{} :meta meta :vars vars}))

(defn- cfg
  ([] (cfg {} {}))
  ([suite global] (g/config-for suite global)))

(defn- selected
  "Ids (`:ns/var` keywords) of the vars a plan's workers run (see
  `brisk.gen.compat/worker-selected`)."
  [plan infos]
  (g/worker-selected plan infos))

(defn- run-ids [config infos & [cli-suites]]
  (selected (select/plan config infos cli-suites) infos))

(defn- all-ids [infos]
  (set (for [{:keys [ns] :as i} infos, v (keys (g/real-vars i))] (keyword (str ns) (str v)))))

(defn- var-ids-of-ns [infos ns-ids]
  (set (for [{:keys [ns] :as i} infos, v (keys (g/real-vars i))
             :when (contains? ns-ids (keyword (str ns)))]
         (keyword (str ns) (str v)))))

(defn- ns-names [plan]
  (mapv :ns (:namespaces (first (:suites plan)))))

;; ---------------------------------------------------------------------------
;; Properties

(def ^:private corpus-and-filters
  (gen/let [corpus g/corpus
            suite-filters (g/filter-gen corpus)
            global (g/filter-gen corpus)]
    {:corpus corpus :suite-filters suite-filters :global global}))

(defn- tree
  "The static tree `select/apply-filters` works on, for one suite `:unit`."
  [corpus suite-filters]
  [{:id :unit
    :filters (update suite-filters :skip-meta conj :kaocha/skip)
    :children (mapv (fn [{:keys [ns meta vars]}]
                      {:id (keyword (str ns))
                       :meta meta
                       :children (mapv (fn [[v m]] {:id (keyword (str ns) (str v)) :meta m}) vars)})
                    corpus)}])

(defn- surviving-vars [filtered-suite]
  (set (for [ns (remove :skip? (:children filtered-suite))
             v (remove :skip? (:children ns))]
         (:id v))))

(defspec decide-is-the-closed-form-of-the-filter-tree 500
  (prop/for-all [{:keys [corpus suite-filters global]} corpus-and-filters]
    (let [{[suite] :suites} (select/apply-filters (tree corpus suite-filters) global)
          by-ns (into {} (map (juxt (comp keyword str :ns) identity)) corpus)]
      (if (:skip? suite)
        true
        (= (surviving-vars suite)
           (set (for [ns-node (:children suite)
                      v (:children ns-node)
                      :let [{:keys [vars ns]} (by-ns (:id ns-node))
                            var-sym (symbol (name (:id v)))
                            spec {:passes (:passes suite) :ns-meta (:meta ns-node)}
                            {:keys [reason]} (select/decide spec ns var-sym (get vars var-sym))]
                      :when (#{:selected :pending} reason)]
                  (:id v))))))))

(defspec without-filters-everything-runs 200
  (prop/for-all [corpus g/corpus]
    (= (all-ids corpus) (run-ids (cfg {:skip-meta []} {}) corpus))))

(defspec plan-never-includes-a-skipped-id 300
  (prop/for-all [corpus g/corpus
                 pick (gen/vector gen/nat 1 4)]
    (let [ids (vec (g/ids-of corpus))
          skip (distinct (map #(nth ids (mod % (count ids))) pick))
          ran (run-ids (cfg {:skip-meta []} {:skip (vec skip)}) corpus)
          skipped-ns (set (filter #(nil? (namespace %)) skip))]
      (and (empty? (set/intersection ran (set skip)))
           (empty? (set/intersection ran (var-ids-of-ns corpus skipped-ns)))))))

(defspec focused-ids-select-exactly-themselves-and-their-namespaces 300
  (prop/for-all [corpus g/corpus
                 pick (gen/vector gen/nat 1 4)]
    (let [ids (vec (g/ids-of corpus))
          focus (vec (distinct (map #(nth ids (mod % (count ids))) pick)))
          ran (run-ids (cfg {:skip-meta []} {:focus focus}) corpus)
          focused-ns (set (filter #(nil? (namespace %)) focus))
          expected (into (set (filter namespace focus)) (var-ids-of-ns corpus focused-ns))]
      (and (set/subset? (set (filter namespace focus)) ran)
           (= expected ran)))))

(defspec skip-meta-tagged-tests-never-run 300
  (prop/for-all [corpus g/corpus
                 key g/meta-key]
    (let [ran (run-ids (cfg {:skip-meta []} {:skip-meta [key]}) corpus)
          tagged (set (for [{:keys [ns meta] :as i} corpus, [v m] (g/real-vars i)
                            :when (or (get meta key) (get m key))]
                        (keyword (str ns) (str v))))]
      (empty? (set/intersection ran tagged)))))

(defspec focus-meta-selects-tagged-tests-and-tagged-namespaces 300
  (prop/for-all [corpus g/corpus
                 key g/meta-key]
    (let [ran (run-ids (cfg {:skip-meta []} {:focus-meta [key]}) corpus)
          carriers (fn [{:keys [meta] :as i}] (or (get meta key) (some #(get % key) (vals (g/real-vars i)))))
          present? (some carriers corpus)
          expected (if present?
                     (set (for [{:keys [ns meta] :as i} corpus
                                :when (carriers i)
                                [v m] (g/real-vars i)
                                :when (or (get meta key) (get m key))]
                            (keyword (str ns) (str v))))
                     (all-ids corpus))]
      (= expected ran))))

(defspec unused-focus-meta-is-ignored-like-kaocha 200
  (prop/for-all [corpus g/corpus]
    ;; :absent is never a generated metadata key
    (let [config (cfg {:skip-meta []} {:focus-meta [:absent]})
          plan (select/plan config corpus [])
          used (select/used-meta (mapcat #(cons (:meta %) (vals (g/real-vars %))) corpus))]
      (and (= (all-ids corpus) (selected plan corpus))
           (= (set (map :ns corpus)) (set (:preload plan)))
           (every? #(= [:absent] (:dropped (select/resolve-focus-meta (:select %) used)))
                   (mapcat :namespaces (:suites plan)))))))

(defspec focus-on-an-invisible-var-selects-it 200
  (prop/for-all [corpus g/corpus]
    (let [hidden (for [{:keys [ns hidden]} corpus, v (keys hidden)] (keyword (str ns) (str v)))]
      (every? #(= #{%} (run-ids (cfg {:skip-meta []} {:focus [%]}) corpus)) hidden))))

(defspec namespaces-are-ordered-by-name 200
  (prop/for-all [corpus g/corpus]
    (let [names (ns-names (select/plan (cfg {:skip-meta []} {}) corpus []))]
      (= names (sort-by str names)))))

(defspec plan-only-contains-requested-suites 100
  (prop/for-all [corpus g/corpus]
    (let [config (update (cfg) :tests conj (assoc (first (:tests (cfg))) :id :other))
          ids #(mapv :id (:suites (select/plan config corpus %)))]
      (and (= [:unit :other] (ids []))
           (= [:other] (ids [:other]))
           (= [:unit] (ids [:unit]))))))

(defspec serial-flag-follows-metadata 200
  (prop/for-all [corpus g/corpus]
    (let [plan (select/plan (cfg {:skip-meta []} {}) corpus [])
          by-ns (into {} (map (juxt :ns identity)) corpus)]
      (every? (fn [{:keys [ns serial?]}]
                (let [{:keys [meta vars]} (by-ns ns)]
                  (= serial? (boolean (some #(or (:synchronized %) (:brisk/serial %))
                                            (cons meta (vals vars)))))))
              (:namespaces (first (:suites plan)))))))

;; ---------------------------------------------------------------------------
;; Examples. Each cites the Kaocha rule it mirrors.

(def ^:private infos
  [(info 'app.a-test {:slow true :x true}
         {'one {} 'two {:slow true} 'three {:kaocha/skip true} 'four {:kaocha/pending true}})
   (info 'app.b-test {'one {:fast true} 'two {}})
   (info 'app.c-test {:synchronized true} {'only {}})])

(deftest defaults-test
  (testing "default skip-meta :kaocha/skip (default_config.edn) hides tagged vars; pending vars are not run"
    (let [plan (select/plan (config/normalize nil {}) infos [])
          ids (selected plan infos)]
      (is (not (contains? ids :app.a-test/three)))
      (is (contains? ids :app.a-test/one))
      (is (= {:run? false :reason :pending}
             (select/decide (:select (first (:namespaces (first (:suites plan))))) 'app.a-test 'four {:kaocha/pending true}))
          "kaocha.testable/run-testable: :kaocha/pending is reported, not run"))))

(deftest skip-beats-pending-test
  (testing "run-testable checks ::skip before :kaocha/pending"
    (let [config (cfg {:skip-meta [:kaocha/skip]} {:skip [:app.a-test/four]})
          plan (select/plan config infos [])
          spec (:select (first (:namespaces (first (:suites plan)))))]
      (is (= {:run? false :reason :skip}
             (select/decide spec 'app.a-test 'four {:kaocha/pending true}))))))

(deftest skip-by-id-test
  (testing "filter-testable: --skip of a ns skips all its vars, of ns/var only that var"
    (is (= #{:app.b-test/one :app.b-test/two :app.c-test/only :app.a-test/one :app.a-test/two}
           (run-ids (cfg {} {:skip [:app.a-test/four]}) infos)))
    (is (= #{:app.b-test/one :app.b-test/two :app.c-test/only}
           (run-ids (cfg {} {:skip [:app.a-test]}) infos)))
    (is (= #{:app.b-test/one :app.c-test/only :app.a-test/one :app.a-test/two :app.a-test/four}
           (run-ids (cfg {:skip-meta []} {:skip [:app.b-test/two :app.a-test/three]}) infos)))))

(deftest skip-meta-test
  (testing "--skip-meta :slow skips tagged vars and (by ns metadata) whole namespaces"
    (is (= #{:app.b-test/one :app.b-test/two :app.c-test/only}
           (run-ids (cfg {} {:skip-meta [:slow]}) infos))
        "app.a-test itself carries :slow")
    (is (= #{:app.a-test/one :app.a-test/two :app.a-test/three :app.a-test/four
             :app.b-test/two :app.c-test/only}
           (run-ids (cfg {:skip-meta []} {:skip-meta [:fast]}) infos)))))

(deftest focus-test
  (testing "filter-testable: --focus on a var runs only that var"
    (is (= #{:app.b-test/two} (run-ids (cfg {} {:focus [:app.b-test/two]}) infos))))
  (testing "--focus on a namespace runs the whole namespace"
    (is (= #{:app.b-test/one :app.b-test/two} (run-ids (cfg {} {:focus [:app.b-test]}) infos))))
  (testing "multiple --focus values are a logical OR"
    (is (= #{:app.b-test/two :app.c-test/only}
           (run-ids (cfg {} {:focus [:app.b-test/two :app.c-test]}) infos))))
  (testing "--focus-meta: ns metadata focuses all of its vars (minus skips)"
    (is (= #{:app.a-test/one :app.a-test/two :app.a-test/four}
           (run-ids (cfg {} {:focus-meta [:x]}) infos)))
    (is (= #{:app.b-test/one} (run-ids (cfg {} {:focus-meta [:fast]}) infos))
        "a ns that is only focused through a var keeps just that var")
    (is (= #{:app.a-test/one :app.a-test/two :app.a-test/four :app.b-test/one}
           (run-ids (cfg {} {:focus-meta [:slow :fast]}) infos))
        "app.a-test carries :slow itself, so all of it runs")))

(deftest focus-and-skip-interplay-test
  (testing "kaocha.plugin.filter/filter-testable: a focused var is never skip-checked, even by its own id"
    (is (= #{:app.b-test/two}
           (run-ids (cfg {} {:focus [:app.b-test/two] :skip [:app.b-test/two]}) infos))))
  (testing "... but once the namespace is the focus match, its vars are skip-checked"
    (is (= #{:app.b-test/one}
           (run-ids (cfg {} {:focus [:app.b-test] :skip [:app.b-test/two]}) infos))))
  (testing "a focused namespace is not skip-checked itself, only its vars"
    (is (= #{:app.a-test/one :app.a-test/two :app.a-test/four}
           (run-ids (cfg {} {:focus-meta [:x] :skip-meta [:x]}) infos))
        "ns metadata :x focuses app.a-test and also matches skip-meta, which only vars consult")))

(deftest suite-focus-test
  (testing "pre-load: --focus naming a suite skips the other suites"
    (let [config (update (cfg) :tests conj (assoc (first (:tests (cfg))) :id :other :test-paths ["it"]))
          plan (select/plan (assoc-in config [:filters :focus] [:other]) infos [])]
      (is (= [:other] (mapv :id (:suites plan))))))
  (testing "a suite's own :focus applies first, then the CLI filters"
    (let [config (cfg {:focus [:app.a-test :app.b-test]} {:skip [:app.b-test/two]})]
      (is (= #{:app.a-test/one :app.a-test/two :app.a-test/four :app.b-test/one}
             (run-ids config infos))))))

(deftest missing-focus-meta-test
  (testing "remove-missing-metadata-keys: a --focus-meta key no test carries is ignored with a warning"
    (let [plan (select/plan (cfg {} {:focus-meta [:nobody-has-this]}) infos [])
          spec (:select (first (:namespaces (first (:suites plan)))))]
      (is (= (all-ids infos) (conj (selected plan infos) :app.a-test/three :app.a-test/four)))
      (is (= [:nobody-has-this] (:resolve-focus-meta spec)) "no visible test has it: workers settle it")
      (is (= ['app.a-test 'app.b-test 'app.c-test] (:preload plan)))
      (is (= {:select (dissoc spec :resolve-focus-meta :variants) :dropped []}
             (select/resolve-focus-meta spec #{:nobody-has-this}))
          "a macro-defined test carries it after all")
      (is (= [:nobody-has-this] (:dropped (select/resolve-focus-meta spec #{:slow}))))
      (is (= "No tests found with metadata key :nobody-has-this. Ignoring --focus-meta :nobody-has-this."
             (select/missing-meta-warning :nobody-has-this)))))
  (testing "a key a visible test carries needs no settling"
    (let [plan (select/plan (cfg {} {:focus-meta [:slow]}) infos [])]
      (is (nil? (:preload plan)))
      (is (not-any? (comp :resolve-focus-meta :select) (mapcat :namespaces (:suites plan))))))
  (testing "the key counts as present when any selected suite carries it"
    (is (= #{:x-test/b} (run-ids (cfg {} {:focus-meta [:slow]})
                                 [(info 'x-test {'a {} 'b {:slow true}})])))))

(deftest unmatched-focus-warning-test
  (let [plan (select/plan (cfg {} {:focus [:no.such-test]}) infos [])]
    (is (= #{} (selected plan infos)))
    (is (some #(re-find #"did not match any tests" %) (:warnings plan)))))

(deftest cli-suites-test
  (testing "kaocha.config/apply-cli-args: positional suite ids restrict the run"
    (let [config (update (cfg) :tests conj (assoc (first (:tests (cfg))) :id :other))]
      (is (= [:other] (mapv :id (:suites (select/plan config infos [:other])))))))
  (testing "kaocha.runner/run: unknown suites are reported with the valid ones"
    (is (= ["No such suite: :nope, :zzz, valid options: :unit."]
           (:errors (select/plan (cfg) infos [:zzz :nope]))))))

(deftest namespace-selection-test
  (testing "namespaces come from the suite's test paths and ns-patterns, sorted"
    (let [config (cfg {:test-paths ["test"] :ns-patterns ["-test$"]} {})
          files [(info 'zeta-test {'a {}}) (info 'alpha-test {'a {}})
                 {:file "test/helper.clj" :ns 'helper :vars {}}
                 {:file "src/alpha_test.clj" :ns 'src-test :vars {'a {}}}]]
      (is (= ['alpha-test 'zeta-test] (ns-names (select/plan config files []))))))
  (testing "unreadable files in the suite become load errors"
    (let [plan (select/plan (cfg) [{:file "test/bad_test.clj" :error "EOF"}] [])]
      (is (= [{:file "test/bad_test.clj" :error "EOF"}] (:load-errors (first (:suites plan)))))))
  (testing "namespaces whose tests are all filtered out are dropped"
    (is (= ['app.b-test] (ns-names (select/plan (cfg {} {:focus [:app.b-test/one]}) infos [])))))
  (testing "a namespace may hold tests the parser can't see"
    (let [files [(info 'macro-test {}) (info 'plain-test {'a {}})]]
      (is (= ['macro-test 'plain-test] (ns-names (select/plan (cfg) files []))))
      (is (= ['plain-test] (ns-names (select/plan (cfg {} {:focus [:plain-test/a]}) files [])))
          "a focus on another namespace's var cannot name its tests")
      (is (= ['macro-test] (ns-names (select/plan (cfg {} {:focus [:macro-test/generated]}) files [])))
          "a focus on a var the parser cannot see keeps its namespace; the worker decides")
      (is (= ['macro-test 'plain-test] (ns-names (select/plan (cfg {} {:focus-meta [:plain]}) files [])))
          "any namespace may hold a macro-defined test with the metadata")
      (is (= ['plain-test] (ns-names (select/plan (cfg {} {:skip [:macro-test]}) files []))))))
  (testing "suites of other types are never planned"
    (let [config (update (cfg) :tests conj {:id :cljs :type :kaocha.type/cljs :test-paths ["test"]
                                            :ns-patterns ["."] :focus [] :skip [] :focus-meta [] :skip-meta []})]
      (is (= [:unit] (mapv :id (:suites (select/plan config infos []))))))))

(deftest serial-test
  (let [plan (select/plan (cfg) infos [])
        by-ns (into {} (map (juxt :ns :serial?)) (:namespaces (first (:suites plan))))]
    (is (= {'app.a-test false 'app.b-test false 'app.c-test true} by-ns)
        "^:synchronized (eftest/hawk) on the ns form")
    (is (true? (:serial? (first (:namespaces (first (:suites (select/plan (cfg) [(info 'x-test {} {'t {:brisk/serial true}})] []))))))))))

(deftest select-spec-is-edn-test
  (testing "the spec crosses the process boundary as EDN"
    (let [spec (:select (first (:namespaces (first (:suites (select/plan (cfg {} {:focus [:app.a-test/one]}) infos [])))))) ]
      (is (= spec (read-string (pr-str spec)))))))

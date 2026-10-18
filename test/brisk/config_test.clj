(ns brisk.config-test
  (:require
   [brisk.config :as config]
   [brisk.gen.compat :as g]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

(defn- load-config
  "read-config then normalize, failing the test on a read error."
  ([text] (load-config text {} {}))
  ([text cli] (load-config text cli {}))
  ([text cli read-opts]
   (let [{:keys [config error]} (config/read-config text read-opts)]
     (is (nil? error))
     (config/normalize config cli))))

(defn- fixture [name]
  (slurp (io/resource (str "compat/" name ".tests.edn"))))

(def ^:private default-suite
  {:id :unit
   :type :kaocha.type/clojure.test
   :test-paths ["test"]
   :source-paths ["src"]
   :ns-patterns ["-test$"]
   :focus []
   :skip []
   :focus-meta []
   :skip-meta [:kaocha/skip]})

;; ---------------------------------------------------------------------------
;; Properties

(defspec read-config-never-throws 300
  (prop/for-all [text gen/string]
    (let [r (config/read-config text)]
      (or (contains? r :config) (string? (:error r))))))

(defspec normalize-never-throws-and-has-the-shape 300
  (prop/for-all [raw gen/any-printable-equatable
                 opts g/cli-options]
    (let [c (config/normalize raw opts)]
      (and (vector? (:tests c))
           (vector? (:warnings c))
           (every? keyword? (map :id (:tests c)))
           (pos-int? (:workers c))))))

(defspec normalize-is-idempotent 300
  (prop/for-all [raw g/raw-config
                 opts g/cli-options]
    (let [once (config/normalize raw opts)]
      (= once (config/normalize once opts)))))

(defspec cli-options-always-win 300
  (prop/for-all [raw g/raw-config
                 opts g/cli-options]
    (let [c (config/normalize raw opts)]
      (every? (fn [[opt k]] (or (not (contains? opts opt)) (= (get opts opt) (get c k))))
              [[:fail-fast :fail-fast?] [:color :color?] [:watch :watch?]
               [:capture-output :capture-output?] [:workers :workers]
               [:isolate :isolate?] [:runtime :runtime]]))))

(defspec cli-filters-replace-config-filters 200
  (prop/for-all [focus (gen/vector g/ns-sym 1 3)
                 skip-meta (gen/vector g/meta-key 1 2)]
    (let [raw {:kaocha.filter/focus [:from-config] :kaocha.filter/skip-meta [:from-config]}
          c (config/normalize raw {:focus focus :skip-meta skip-meta})]
      (and (= focus (get-in c [:filters :focus]))
           (= skip-meta (get-in c [:filters :skip-meta]))))))

(defspec every-suite-is-complete 200
  (prop/for-all [raw g/raw-config]
    (every? (fn [s]
              (and (keyword? (:id s))
                   (every? vector? ((juxt :test-paths :source-paths :ns-patterns :focus :skip
                                         :focus-meta :skip-meta) s))
                   (some #{:kaocha/skip} (:skip-meta s))))
            (:tests (config/normalize raw {})))))

(defspec unsupported-plugins-always-warn 200
  (prop/for-all [raw g/raw-config]
    (let [c (config/normalize raw {})
          supported #{:kaocha.plugin/filter :kaocha.plugin/capture-output :kaocha.plugin/randomize
                      :kaocha.plugin/print-invocations :kaocha.plugin/profiling}]
      (= (set (remove supported (:plugins c)))
         (set (keep #(some-> (re-find #"^unsupported plugin (\S+) ignored$" %) second read-string)
                    (:warnings c)))))))

;; ---------------------------------------------------------------------------
;; Defaults (kaocha/default_config.edn, kaocha.config/normalize)

(deftest missing-file-is-kaocha-defaults-test
  (let [c (config/normalize nil {})]
    (is (= [default-suite] (:tests c)))
    (is (= [:kaocha.plugin/randomize :kaocha.plugin/filter :kaocha.plugin/capture-output]
           (:plugins c)))
    (is (= {:fail-fast? false :color? true :capture-output? true :watch? false
            :reporter :live :runtime :bb :isolate? false}
           (select-keys c [:fail-fast? :color? :capture-output? :watch? :reporter :runtime :isolate?])))
    (is (= [] (:warnings c)))))

(deftest empty-v1-config-equals-missing-file-test
  (testing "secrets and local-coder use `#kaocha/v1 {}`"
    (is (= (config/normalize nil {}) (load-config (fixture "secrets"))))
    (is (= (config/normalize nil {}) (load-config (fixture "local-coder"))))))

(deftest real-project-configs-test
  (testing "kaocha.config/normalize-test-suite: only :id and :test-paths are overridden"
    (is (= [(assoc default-suite :test-paths ["test/hosted_clay"])]
           (:tests (load-config (fixture "hosted-clay")))))
    (is (= [(assoc default-suite :test-paths ["test/reader"])]
           (:tests (load-config (fixture "reader")))))))

(deftest both-key-styles-test
  (testing "kaocha.config/normalize-test-suite renames unqualified keys"
    (let [unq {:tests [{:id :a :test-paths ["x"] :ns-patterns ["y"] :source-paths ["z"]
                        :skip [:s] :focus [:f] :skip-meta [:sm] :focus-meta [:fm]}]}
          qual {:kaocha/tests [{:kaocha.testable/id :a :kaocha/test-paths ["x"] :kaocha/ns-patterns ["y"]
                                :kaocha/source-paths ["z"] :kaocha.filter/skip [:s]
                                :kaocha.filter/focus [:f] :kaocha.filter/skip-meta [:sm]
                                :kaocha.filter/focus-meta [:fm]}]}
          expected {:id :a :type :kaocha.type/clojure.test :test-paths ["x"] :ns-patterns ["y"]
                    :source-paths ["z"] :skip [:s] :focus [:f] :skip-meta [:kaocha/skip :sm]
                    :focus-meta [:fm]}]
      (is (= [expected] (:tests (config/normalize unq {}))))
      (is (= [expected] (:tests (config/normalize qual {})))))))

(deftest merge-semantics-test
  (testing "kaocha.config/merge-config: paths replace the defaults, skip-meta appends"
    (let [s (first (:tests (config/normalize {:tests [{:test-paths ["a" "b"] :skip-meta [:slow]}]} {})))]
      (is (= ["a" "b"] (:test-paths s)))
      (is (= [:kaocha/skip :slow] (:skip-meta s)))))
  (testing "^:replace on skip-meta drops the default :kaocha/skip (meta-merge)"
    (let [{:keys [config]} (config/read-config "{:tests [{:skip-meta ^:replace [:slow]}]}")
          s (first (:tests (config/normalize config {})))]
      (is (= [:slow] (:skip-meta s)))))
  (testing "^:prepend puts user entries before the defaults"
    (let [{:keys [config]} (config/read-config "{:tests [{:skip-meta ^:prepend [:slow]}]}")
          s (first (:tests (config/normalize config {})))]
      (is (= [:slow :kaocha/skip] (:skip-meta s)))))
  (testing "suites replace the default suite entirely (replace-by-default on :kaocha/tests)"
    (is (= [:a :b] (mapv :id (:tests (config/normalize {:tests [{:id :a} {:id :b}]} {})))))
    (is (= [] (:tests (config/normalize {:tests []} {})))))
  (testing "plugins append to the defaults"
    (is (= [:kaocha.plugin/randomize :kaocha.plugin/filter :kaocha.plugin/capture-output
            :kaocha.plugin/profiling]
           (:plugins (config/normalize {:plugins [:profiling]} {})))))
  (testing "kaocha.plugin/normalize-name: bare names gain the kaocha.plugin namespace"
    (is (some #{:kaocha.plugin/notifier} (:plugins (config/normalize {:plugins [:notifier]} {})))))
  (testing "a non-collection path exits with an error in Kaocha; brisk reports :errors and uses defaults"
    (let [c (config/normalize {:tests [{:test-paths "test"}]} {})]
      (is (= [default-suite] (:tests c)))
      (is (some #(str/includes? % "test-paths") (:errors c))))))

;; ---------------------------------------------------------------------------
;; CLI overrides (kaocha.config/apply-cli-opts)

(deftest cli-override-test
  (let [raw {:fail-fast? true :color? false :watch? false :capture-output? false}]
    (testing "config values apply without the CLI"
      (is (= {:fail-fast? true :color? false :watch? false :capture-output? false}
             (select-keys (config/normalize raw {}) [:fail-fast? :color? :watch? :capture-output?]))))
    (testing "the CLI wins"
      (is (= {:fail-fast? false :color? true :watch? true :capture-output? true}
             (select-keys (config/normalize raw {:fail-fast false :color true :watch true :capture-output true})
                          [:fail-fast? :color? :watch? :capture-output?])))))
  (testing "--plugin appends (distinct), as apply-cli-opts does"
    (is (= [:kaocha.plugin/randomize :kaocha.plugin/filter :kaocha.plugin/capture-output
            :kaocha.plugin/notifier]
           (:plugins (config/normalize nil {:plugin [:kaocha.plugin/notifier :kaocha.plugin/filter]})))))
  (testing "filters: the CLI replaces config-level filters key by key (kaocha.plugin.filter/config)"
    (is (= {:focus [:a/b] :skip [:x] :focus-meta [] :skip-meta []}
           (:filters (config/normalize {:kaocha.filter/focus [:old] :kaocha.filter/skip [:x]}
                                       {:focus [:a/b]})))))
  (testing "brisk's own options"
    (let [c (config/normalize nil {:workers 3 :isolate true :runtime :jvm})]
      (is (= [3 true :jvm] ((juxt :workers :isolate? :runtime) c))))
    (is (= 6 (:workers (config/normalize nil {} {:cpus 6}))))
    (is (= 8 (:workers (config/normalize nil {} {:cpus 64}))))))

;; ---------------------------------------------------------------------------
;; Warnings

(deftest warnings-test
  (testing "plugins brisk treats as built in or harmless do not warn"
    (is (= [] (:warnings (config/normalize {:plugins [:profiling :print-invocations]} {})))))
  (testing "other plugins warn, by name"
    (is (= ["unsupported plugin :kaocha.plugin/notifier ignored"
            "unsupported plugin :my.plugin/thing ignored"]
           (:warnings (config/normalize {:plugins [:notifier :my.plugin/thing]} {})))))
  (testing "unsupported options warn rather than vanish"
    (let [w (:warnings (config/normalize {:bindings {'a/b 1} :kaocha.hooks/pre-load 'x/y} {}))]
      (is (some #(str/includes? % ":kaocha/bindings") w))
      (is (some #(str/includes? % ":kaocha.hooks/pre-load") w))))
  (testing "suites of other types are kept but flagged"
    (let [c (config/normalize {:tests [{:id :cljs :type :kaocha.type/cljs}]} {})]
      (is (= [:cljs] (mapv :id (:tests c))))
      (is (some #(str/includes? % "unsupported type") (:warnings c)))))
  (testing "unknown suite keys warn"
    (is (some #(str/includes? % ":kaocha.hooks/pre-test")
              (:warnings (config/normalize {:tests [{:kaocha.hooks/pre-test 'x/y}]} {})))))
  (testing "--seed is accepted but warned about, randomize ordering is per worker"
    (is (some #(str/includes? % "--seed") (:warnings (config/normalize nil {:seed 1})))))
  (testing "a non-map config falls back to defaults with a warning"
    (let [c (config/normalize [1 2] {})]
      (is (= [default-suite] (:tests c)))
      (is (seq (:warnings c))))))

(deftest reporter-mapping-test
  (testing "the Kaocha default reporter maps to brisk's live reporter"
    (is (= :live (:reporter (config/normalize nil {})))))
  (testing "documentation is the one reporter with its own brisk equivalent"
    (is (= :documentation (:reporter (config/normalize {:reporter ['kaocha.report/documentation]} {}))))
    (is (= :documentation (:reporter (config/normalize nil {:reporter ['kaocha.report/documentation]})))))
  (testing "the CLI reporter beats the config one"
    (is (= :live (:reporter (config/normalize {:reporter ['kaocha.report/documentation]}
                                              {:reporter ['kaocha.report/dots]})))))
  (testing "custom reporters fall back to :live with a warning"
    (let [c (config/normalize {:reporter ['my.ns/reporter]} {})]
      (is (= :live (:reporter c)))
      (is (some #(str/includes? % "my.ns/reporter") (:warnings c))))))

;; ---------------------------------------------------------------------------
;; Reading: Aero and Kaocha tags

(deftest read-config-basics-test
  (is (= {:config nil :warnings []} (config/read-config "")))
  (is (= {:config {:a 1} :warnings []} (config/read-config "{:a 1}")))
  (is (string? (:error (config/read-config "{:a"))))
  (is (string? (:error (config/read-config "#nope/tag 1"))) "unknown tags are errors, as in Aero")
  (is (= {:a 1} (:config (config/read-config "#kaocha/v1 {:a 1}")))
      "kaocha.config reader 'kaocha/v1: defaults are applied by normalize, once")
  (testing "the deprecated #kaocha literal still reads, with a warning"
    (let [r (config/read-config "#kaocha {}")]
      (is (= {} (:config r)))
      (is (= 1 (count (:warnings r)))))))

(deftest aero-tags-test
  (let [read #(:config (config/read-config %1 %2))
        opts {:env {"HOME" "/h" "N" "42" "FLAG" "true"} :props {"p" "pv"}
              :hostname "box" :user "kira"}]
    (testing "#env, #envf, #prop, #long, #double, #keyword, #boolean, #join"
      (is (= "/h" (read "#env \"HOME\"" opts)))
      (is (nil? (read "#env \"MISSING\"" opts)))
      (is (= "/h/x" (read "#envf [\"%s/x\" \"HOME\"]" opts)))
      (is (= "pv" (read "#prop \"p\"" opts)))
      (is (= 42 (read "#long #env \"N\"" opts)))
      (is (= 1.5 (read "#double \"1.5\"" opts)))
      (is (= :ci (read "#keyword \"ci\"" opts)))
      (is (true? (read "#boolean #env \"FLAG\"" opts)))
      (is (= "a-b" (read "#join [\"a\" \"-\" \"b\"]" opts))))
    (testing "#or takes the first truthy value"
      (is (= "/h" (read "#or [#env \"NOPE\" #env \"HOME\" \"x\"]" opts)))
      (is (= "x" (read "#or [#env \"NOPE\" \"x\"]" opts)))
      (is (nil? (read "#or [#env \"NOPE\"]" opts))))
    (testing "#profile picks by profile, falls back to :default, understands set keys"
      (is (= 2 (read "#profile {:default 1 :ci 2}" {:profile :ci})))
      (is (= 1 (read "#profile {:default 1 :ci 2}" {:profile :other})))
      (is (= 3 (read "#profile {#{:a :b} 3 :default 1}" {:profile :b})))
      (is (= 1 (read "#profile {:default 1}" {})) "no profile given means :default")
      (is (= 2 (read "#profile {:default 1 :ci 2}" {:env {"CI" "true"}}))
          "kaocha.config/read-config-source: CI=true selects :ci"))
    (testing "#hostname and #user"
      (is (= :b (read "#hostname {\"box\" :b :default :d}" opts)))
      (is (= :k (read "#user {\"kira\" :k :default :d}" opts))))
    (testing "#merge and #meta-merge"
      (is (= {:a 1 :b 3} (read "#merge [{:a 1 :b 2} {:b 3}]" {})))
      (is (= {:a [1 2]} (read "#meta-merge [{:a [1]} {:a [2]}]" {})))
      (is (= {:a [2]} (read "#meta-merge [{:a [1]} {:a ^:replace [2]}]" {}))))
    (testing "#ref, including references to values that are themselves tagged"
      (is (= {:a 1 :b 1} (read "{:a 1 :b #ref [:a]}" {})))
      (is (= {:a "x/h" :b "x/h"} (read "{:b #ref [:a] :a #join [\"x/\" #env \"HOME\"]}" {:env {"HOME" "h"}})))
      (is (= {:a nil} (read "{:a #ref [:missing]}" {})) "unresolvable refs become nil, as in Aero"))
    (testing "#read-edn"
      (is (= {:a 1} (read "#read-edn \"{:a 1}\"" {}))))))

(deftest include-test
  (let [files {"inc.edn" "{:fromfile #profile {:default :d :ci :c}}"
               "outer.edn" "{:nested #include \"inc.edn\"}"}
        include (fn [path _from] (get files path))]
    (is (= {:x {:fromfile :d}} (:config (config/read-config "{:x #include \"inc.edn\"}" {:include include}))))
    (is (= {:x {:fromfile :c}} (:config (config/read-config "{:x #include \"inc.edn\"}"
                                                            {:include include :profile :ci}))))
    (is (= {:x {:nested {:fromfile :d}}}
           (:config (config/read-config "{:x #include \"outer.edn\"}" {:include include}))))
    (is (= {:x {:aero/missing-include "nope.edn"}}
           (:config (config/read-config "{:x #include \"nope.edn\"}" {:include include})))
        "Aero's behaviour for a missing include")
    (is (string? (:error (config/read-config "#include \"loop.edn\"" {:include (fn [_ _] "#include \"loop.edn\"")}))))))

(deftest rich-fixture-test
  (let [text (fixture "rich")
        c (load-config text {} {:env {}})
        c-ci (load-config text {} {:env {"FAIL_FAST" "true"} :profile :focus})]
    (is (= [:unit :integration] (mapv :id (:tests c))))
    (is (= ["-test$" "^it\\."] (:ns-patterns (first (:tests c)))))
    (is (= [:kaocha/skip :slow] (:skip-meta (first (:tests c)))))
    (is (= [] (:focus-meta (first (:tests c)))))
    (is (= [:wip] (:focus-meta (first (:tests c-ci)))))
    (is (= ["test/integration"] (:test-paths (second (:tests c)))))
    (is (= '[my.app.flaky-test] (:skip (second (:tests c)))))
    (is (= false (:fail-fast? c)))
    (is (= true (:fail-fast? c-ci)))
    (is (= false (:color? c)))
    (is (= :documentation (:reporter c)))
    (is (= ["unsupported plugin :kaocha.plugin/notifier ignored"] (:warnings c)))))

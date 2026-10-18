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

(def ^:private plugins-warning
  #"^brisk does not run these plugins and ignores their options: (.+)$")

(defspec unsupported-plugins-always-warn-in-one-line 200
  (prop/for-all [raw g/raw-config]
    (let [c (config/normalize raw {})
          supported #{:kaocha.plugin/filter :kaocha.plugin/capture-output :kaocha.plugin/randomize
                      :kaocha.plugin/print-invocations :kaocha.plugin/profiling
                      :kaocha.plugin/hooks :kaocha.plugin/junit-xml}
          lines (keep #(second (re-find plugins-warning %)) (:warnings c))]
      (and (<= (count lines) 1)
           (= (set (remove supported (:plugins c)))
              (set (map read-string (some-> (first lines) (str/split #", ")))))))))

(deftest plugins-that-cannot-load-fail-like-kaocha
  ;; kaocha.plugin/register, try-load-third-party-lib
  (let [none (constantly false)]
    (is (= "Couldn't load plugin :kaocha.plugin/foo. Failed to load namespace kaocha.plugin.foo. This could be caused by a misspelling or a missing dependency."
           (config/plugin-load-error :kaocha.plugin/foo none)))
    (is (= "Couldn't load plugin :my.team/thing. Failed to load namespaces my.team.thing and my.team. This could be caused by a misspelling or a missing dependency."
           (config/plugin-load-error :my.team/thing none)))
    (is (nil? (config/plugin-load-error :my.team/thing #{'my.team})) "a namespace that loads may define it")
    (is (= [:my.team/thing] (:unknown-plugins (config/normalize {:plugins [:my.team/thing :notifier :hooks]} {}))))))

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
  (testing "other plugins warn, by name, in one line, and their config keys are quiet"
    (is (= ["brisk does not run these plugins and ignores their options: :kaocha.plugin/notifier, :my.plugin/thing"]
           (:warnings (config/normalize {:plugins [:notifier :my.plugin/thing]
                                         :kaocha.plugin.notifier/command "say"} {})))))
  (testing "--randomize and --seed: one warning"
    (is (= ["--randomize and --seed are ignored: brisk does not randomize test order"]
           (:warnings (config/normalize nil {:randomize true :seed 3}))))
    (is (= [] (:warnings (config/normalize nil {:randomize false})))))
  (testing "unsupported options warn rather than vanish"
    (let [w (:warnings (config/normalize {:kaocha/nonsense 1 :kaocha.hooks/pre-load 'x/y} {}))]
      (is (some #(str/includes? % ":kaocha/nonsense") w))
      (is (some #(str/includes? % ":kaocha.hooks/pre-load") w))))
  (testing ":kaocha/bindings are supported, under either key"
    (is (= {'a/b 1} (:bindings (config/normalize {:bindings {'a/b 1}} {}))))
    (is (= {'a/b 1} (:bindings (config/normalize {:kaocha/bindings {'a/b 1}} {}))))
    (is (empty? (:warnings (config/normalize {:bindings {'a/b 1}} {})))))
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
  (testing "the implicit default does not warn"
    (is (not-any? #(str/includes? % "reporter") (:warnings (config/normalize nil {})))))
  (testing "Kaocha's own reporters map to brisk's output without a warning"
    (doseq [r ['kaocha.report/dots 'kaocha.report.progress/report 'kaocha.report/report 'clojure.test/report]]
      (let [c (config/normalize nil {:reporter [r]})]
        (is (= :live (:reporter c)))
        (is (not-any? #(str/includes? % "reporter") (:warnings c)) (str r))
        (is (nil? (:reporters c)))))
    (is (empty? (:warnings (config/normalize {:kaocha/reporter ['kaocha.report/dots]} {})))))
  (testing "Kaocha reporters with no brisk equivalent warn"
    (is (some #(str/includes? % "kaocha.report/tap")
              (:warnings (config/normalize nil {:reporter ['kaocha.report/tap]})))))
  (testing "the project's own reporters are kept for the workers, beside brisk's output"
    (let [c (config/normalize {:reporter ['my.ns/reporter 'kaocha.report/documentation]} {})]
      (is (= :documentation (:reporter c)))
      (is (= ['my.ns/reporter] (:reporters c)))
      (is (empty? (:warnings c)))
      (is (= ['my.ns/reporter] (:reporters (config/normalize c {}))) "normalizing again keeps them"))))

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
    (is (= ["brisk does not run these plugins and ignores their options: :kaocha.plugin/notifier"] (:warnings c)))))

;; ---------------------------------------------------------------------------
;; Hooks (kaocha.plugin.hooks) and :brisk/worker-setup

(defn- hook-target [k]
  (case k
    :kaocha.hooks/before :pre-test
    :kaocha.hooks/after :post-test
    (keyword (name k))))

(defn- non-empty? [v] (if (sequential? v) (seq v) (some? v)))

(defspec every-hook-key-is-used-or-warned-about 300
  (prop/for-all [raw g/raw-config]
    (let [c (config/normalize raw {})
          warned? (fn [k] (some #(str/includes? % (pr-str k)) (:warnings c)))
          used? (fn [hooks k] (seq (get hooks (hook-target k))))
          check (fn [m hooks]
                  (every? (fn [[k v]]
                            (or (not (non-empty? v))
                                (if (= :brisk/worker-setup k)
                                  (or (seq (:worker-setup m)) (warned? k))
                                  (or (used? hooks k) (warned? k)))))
                          (filter (fn [[k]] (or (= :brisk/worker-setup k) (= "kaocha.hooks" (namespace k))))
                                  (if (map? raw) raw {}))))]
      (check c (:hooks c)))))

(deftest hooks-plugin-config
  (let [raw {:plugins [:kaocha.plugin/hooks]
             :kaocha.hooks/pre-load ['my.hooks/load!]
             :kaocha.hooks/before 'my.hooks/before!
             :kaocha.hooks/pre-test ['my.hooks/pre!]
             :kaocha.hooks/post-summary ['my.hooks/summary!]
             :kaocha.hooks/config ['my.hooks/config!]
             :brisk/worker-setup ['my.hooks/isolate!]
             :tests [{:id :unit
                      :kaocha.hooks/after ['my.hooks/after!]
                      :kaocha.hooks/pre-run ['my.hooks/nope]
                      :brisk/worker-setup ['my.hooks/unit-setup!]}]}
        c (config/normalize raw {})]
    (testing "kaocha.plugin.hooks/rename-aliases: before and after are pre-test and post-test"
      (is (= '{:pre-load [my.hooks/load!]
               :pre-test [my.hooks/pre! my.hooks/before!]
               :post-summary [my.hooks/summary!]}
             (:hooks c)))
      (is (= '{:post-test [my.hooks/after!]} (:hooks (first (:tests c))))))
    (is (= '[my.hooks/isolate!] (:worker-setup c)))
    (is (= '[my.hooks/unit-setup!] (:worker-setup (first (:tests c)))))
    (testing "what cannot work is a warning, never silent"
      (is (some #(str/includes? % ":kaocha.hooks/config is not supported") (:warnings c)))
      (is (some #(str/includes? % "unsupported hook :kaocha.hooks/pre-run (suite :unit)") (:warnings c)))
      (is (not-any? #(str/includes? % "unsupported plugin") (:warnings c))))
    (is (= c (config/normalize c {})) "normalizing twice changes nothing")))

(deftest hooks-need-the-plugin
  (let [c (config/normalize {:kaocha.hooks/pre-load ['my.hooks/load!]
                             :brisk/worker-setup ['my.hooks/isolate!]} {})]
    (is (nil? (:hooks c)))
    (is (= '[my.hooks/isolate!] (:worker-setup c)) ":brisk/worker-setup needs no plugin")
    (is (some #(str/includes? % "hooks :kaocha.hooks/pre-load ignored: :kaocha.plugin/hooks is not in :plugins")
              (:warnings c)))))

(deftest bad-hook-entries-warn
  (let [c (config/normalize {:plugins [:hooks] :kaocha.hooks/pre-load ['unqualified 'ok/fine 3]} {})]
    (is (= '{:pre-load [ok/fine]} (:hooks c)))
    (is (= 2 (count (filter #(str/includes? % "is not a qualified symbol or a list form") (:warnings c)))))))

(deftest brisk-keys-are-validated
  (testing "a value brisk cannot use is a config error, not a crash or a guess"
    (is (= [":workers must be a positive integer, got \"4\""]
           (:errors (config/normalize {:workers "4"} {}))))
    (is (= [":workers must be a positive integer, got 0"]
           (:errors (config/normalize {:workers 0} {}))))
    (is (= [":runtime must be :bb or :jvm, got :jvmx"]
           (:errors (config/normalize {:runtime :jvmx} {}))))
    (is (= :bb (:runtime (config/normalize {:runtime :jvmx} {} {:bb? true}))) "defaults are kept"))
  (testing "good values pass"
    (is (= [] (:errors (config/normalize {:workers 3 :runtime :jvm} {}))))))

(deftest values-brisk-cannot-use-are-errors
  (testing "brisk's own keys and :kaocha/bindings (which crashes Kaocha's binding-map)"
    (doseq [[raw msg] [[{:preload? "yes"} ":preload? must be true or false"]
                       [{:isolate? 1} ":isolate? must be true or false"]
                       [{:kaocha/bindings [1 2]} ":kaocha/bindings must be a map"]
                       [{:bindings [1 2]} ":kaocha/bindings must be a map"]]]
      (is (some #(str/includes? % msg) (:errors (config/normalize raw {}))) (pr-str raw))))
  (testing "a suite id Kaocha's spec rejects (exit 252)"
    (is (= ["suite id \"unit\" should be a keyword (:kaocha.testable/id)"]
           (:invalid (config/normalize {:tests [{:id "unit"}]} {}))))
    (is (nil? (:invalid (config/normalize {:tests [{:id :unit}]} {})))))
  (testing "--print-result runs once even with --watch"
    (let [c (config/normalize {:watch? true} {:print-result true})]
      (is (false? (:watch? c)))
      (is (some #(str/includes? % "--watch ignored with --print-result") (:warnings c))))))

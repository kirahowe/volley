(ns brisk.diff.kaocha-diff
  "Differential tests: brisk's pure compatibility core against Kaocha 1.91.1392
  itself, on the same inputs. Needs Kaocha on the classpath, so it is not part
  of `bb test`; run it with `bb test:diff`."
  (:require
   [aero.core :as aero]
   [brisk.cli :as cli]
   [brisk.config :as config]
   [brisk.discover :as discover]
   [brisk.gen.compat :as g]
   [brisk.select :as select]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.tools.cli :as tcli]
   [kaocha.config :as kc]
   [kaocha.plugin :as plugin]
   [kaocha.plugin.filter :as kf]
   [kaocha.runner :as runner]
   [kaocha.testable :as testable]
   [lambdaisland.tools.namespace.parse :as ctn-parse])
  (:import
   (java.io StringReader)))

;; ---------------------------------------------------------------------------
;; CLI

(def ^:private kaocha-cli-specs
  (let [chain (plugin/load-all [:kaocha.plugin/randomize :kaocha.plugin/filter
                                :kaocha.plugin/capture-output :kaocha.plugin/profiling])]
    (plugin/run-hook* chain :kaocha.hooks/cli-options @#'runner/cli-options)))

(defn- brisk-only? [token]
  (boolean (re-find #"^--(no-)?(workers|runtime|isolate|aliases)" token)))

(defn- kaocha-parse [argv]
  (let [{:keys [options arguments errors]} (tcli/parse-opts argv kaocha-cli-specs)]
    {:options options
     :suites (set (map runner/parse-kw arguments))
     :errors (count errors)}))

(defn- brisk-parse [argv]
  (let [{:keys [options suites errors]} (cli/parse-args argv)]
    {:options options :suites (set suites) :errors (count errors)}))

(defspec cli-matches-tools-cli 500
  (prop/for-all [argv (gen/fmap #(vec (remove brisk-only? %)) g/argv)]
    (= (kaocha-parse argv) (brisk-parse argv))))

(defspec cli-matches-tools-cli-on-arbitrary-strings 300
  (prop/for-all [argv (gen/vector (gen/one-of [gen/string-alphanumeric
                                               (gen/fmap #(str "-" %) gen/string-alphanumeric)
                                               (gen/fmap #(str "--" %) gen/string-alphanumeric)
                                               (gen/elements ["--focus" "-c" "--no-color" "--seed"])])
                                  0 8)]
    (= (kaocha-parse argv) (brisk-parse argv))))

;; ---------------------------------------------------------------------------
;; Config

(defn- kaocha-load
  "What `kaocha.config/load-config` + `apply-cli` produce for `text`."
  [text cli-options]
  (-> (aero/read-config (StringReader. text) {:profile :default})
      (kc/apply-cli cli-options nil)))

(defn- cli-filters [cli-options]
  (into {}
        (map (fn [k] [k (vec (get cli-options k))]))
        [:focus :skip :focus-meta :skip-meta]))

(defn- kaocha-view [kconfig cli-options]
  (let [flag (:capture-output cli-options)]
    {:tests (mapv (fn [s] {:id (:kaocha.testable/id s)
                           :type (:kaocha.testable/type s)
                           :test-paths (vec (:kaocha/test-paths s))
                           :source-paths (vec (:kaocha/source-paths s))
                           :ns-patterns (vec (:kaocha/ns-patterns s))
                           :focus (vec (:kaocha.filter/focus s))
                           :skip (vec (:kaocha.filter/skip s))
                           :focus-meta (vec (:kaocha.filter/focus-meta s))
                           :skip-meta (vec (distinct (:kaocha.filter/skip-meta s)))})
                  (:kaocha/tests kconfig))
     :plugins (vec (distinct (map plugin/normalize-name (:kaocha/plugins kconfig))))
     :fail-fast? (boolean (:kaocha/fail-fast? kconfig))
     :color? (boolean (:kaocha/color? kconfig))
     :watch? (boolean (:kaocha/watch? kconfig))
     :capture-output? (if (some? flag)
                        flag
                        (get kconfig :kaocha.plugin.capture-output/capture-output? true))
     :filters (let [cli (cli-filters cli-options)]
                (into {}
                      (map (fn [k]
                             [k (vec (if (seq (get cli k))
                                       (get cli k)
                                       (get kconfig (keyword "kaocha.filter" (name k)))))]))
                      [:focus :skip :focus-meta :skip-meta]))}))

(defn- brisk-view [text cli-options]
  (let [{:keys [config error]} (config/read-config text {})]
    (assert (nil? error) error)
    (-> (config/normalize config cli-options)
        (select-keys [:tests :plugins :fail-fast? :color? :watch? :capture-output? :filters])
        ;; hooks are compared by brisk.config-test; Kaocha keeps them as raw keys
        (update :tests (fn [ts] (mapv #(dissoc % :hooks :worker-setup) ts))))))

(defn- compare-config [text cli-options]
  (= (kaocha-view (kaocha-load text cli-options) cli-options)
     (brisk-view text cli-options)))

(defspec normalize-matches-kaocha 300
  (prop/for-all [raw (gen/fmap #(or % {}) g/raw-config)
                 opts g/cli-options]
    ;; Kaocha ignores --no-watch when tests.edn says :watch? true; brisk honours it.
    (let [opts (cond-> opts (false? (:watch opts)) (dissoc :watch))]
      (compare-config (str "#kaocha/v1 " (pr-str raw)) opts))))

(deftest real-project-configs-match-kaocha-test
  (doseq [f ["hosted-clay" "reader" "secrets" "local-coder" "rich"]]
    (let [text (slurp (io/resource (str "compat/" f ".tests.edn")))]
      (testing f
        (is (compare-config text {}))
        (is (compare-config text {:fail-fast true :color false :focus [:a/b] :skip-meta [:slow]
                                  :plugin [:kaocha.plugin/notifier]}))))))

(deftest missing-config-matches-kaocha-defaults-test
  (is (= (kaocha-view (kc/apply-cli (kc/default-config) {} nil) {})
         (-> (config/normalize nil {})
             (select-keys [:tests :plugins :fail-fast? :color? :watch? :capture-output? :filters])))))

;; ---------------------------------------------------------------------------
;; Selection

(defn- kaocha-selected
  "Run the real kaocha.plugin.filter pre-load and post-load hooks over a tree
  built from `suites` (`[{:id :focus .. :infos [..]}]`) and return the ids of
  the vars that survive. The tree has every var Kaocha would load, including
  the `:hidden` ones brisk's parser cannot see."
  [suites global cli-suites]
  (let [suite-ts (mapv (fn [{:keys [id focus skip focus-meta skip-meta]}]
                         (cond-> {:kaocha.testable/id id
                                  :kaocha.testable/type :kaocha.type/clojure.test
                                  :kaocha.filter/skip-meta skip-meta}
                           (seq focus) (assoc :kaocha.filter/focus focus)
                           (seq skip) (assoc :kaocha.filter/skip skip)
                           (seq focus-meta) (assoc :kaocha.filter/focus-meta focus-meta)))
                       suites)
        global-keys (cond-> {}
                      (seq (:focus global)) (assoc :kaocha.filter/focus (:focus global))
                      (seq (:skip global)) (assoc :kaocha.filter/skip (:skip global))
                      (seq (:focus-meta global)) (assoc :kaocha.filter/focus-meta (:focus-meta global))
                      (seq (:skip-meta global)) (assoc :kaocha.filter/skip-meta (:skip-meta global)))
        config (-> (merge {:kaocha/tests suite-ts} global-keys)
                   (kc/apply-cli-args (map #(keyword (name %)) cli-suites)))
        config (kf/filter-pre-load-hook config)
        plan (merge (dissoc config :kaocha/tests)
                    {:kaocha.test-plan/tests
                     (mapv (fn [t {:keys [infos]}]
                             (assoc t :kaocha.test-plan/tests
                                    (mapv (fn [{:keys [ns meta] :as info}]
                                            {:kaocha.testable/type :kaocha.type/ns
                                             :kaocha.testable/id (keyword (str ns))
                                             :kaocha.testable/meta meta
                                             :kaocha.test-plan/tests
                                             (mapv (fn [[v m]]
                                                     {:kaocha.testable/type :kaocha.type/var
                                                      :kaocha.testable/id (keyword (str ns) (str v))
                                                      :kaocha.testable/meta m})
                                                   (g/real-vars info))})
                                          infos)))
                           (:kaocha/tests config) suites)})
        result (kf/filter-post-load-hook plan)]
    (->> (testable/test-seq result)
         (filter #(= :kaocha.type/var (:kaocha.testable/type %)))
         (map :kaocha.testable/id)
         set)))

(defn- brisk-selected [suites global cli-suites]
  (let [infos (mapcat :infos suites)
        config {:tests (mapv (fn [{:keys [id focus skip focus-meta skip-meta infos]}]
                               {:id id :type :kaocha.type/clojure.test
                                :test-paths [(str "test/" (name id))] :ns-patterns ["."]
                                :focus focus :skip skip :focus-meta focus-meta :skip-meta skip-meta
                                :infos infos})
                             suites)
                :filters global}
        plan (select/plan config infos cli-suites)]
    (g/worker-selected plan infos)))

(def ^:private suites-gen
  "Two suites over disjoint namespaces, each with its own filters, plus
  global (CLI) filters and an optional suite-id restriction."
  (gen/let [corpus g/corpus
            own-a (g/filter-gen corpus)
            own-b (g/filter-gen corpus)
            global (g/filter-gen corpus)
            cli-suites (gen/elements [[] [] [:a] [:b] [:a :b] [:zzz]])
            skip-meta-extra (gen/vector g/meta-key 0 1)]
    (let [[xs ys] (split-at (quot (count corpus) 2) corpus)
          place (fn [id infos]
                  (mapv #(assoc % :file (str "test/" (name id) "/" (:ns %) ".clj")) infos))
          own #(update % :skip-meta (comp vec distinct into) [:kaocha/skip])]
      {:suites [(assoc (own own-a) :id :a :infos (place :a xs))
                (assoc (own (update own-b :skip-meta into skip-meta-extra)) :id :b :infos (place :b ys))]
       :global global
       :cli-suites cli-suites})))

(defspec selection-matches-kaocha-filter-plugin 1500
  (prop/for-all [{:keys [suites global cli-suites]} suites-gen]
    (let [suites (mapv #(update % :focus-meta vec) suites)]
      (= (kaocha-selected suites global (filter #{:a :b} cli-suites))
         (brisk-selected suites global (filter #{:a :b} cli-suites))))))

(deftest unknown-suite-message-matches-kaocha-test
  (let [plan (select/plan (config/normalize nil {}) [] [:nope])]
    (is (= ["No such suite: :nope, valid options: :unit."] (:errors plan)))))

;; ---------------------------------------------------------------------------
;; Discovery

(defspec requires-match-tools-namespace 300
  (prop/for-all [{:keys [text]} g/ns-file]
    ;; lambdaisland.tools.namespace (what Kaocha uses to find namespaces)
    ;; reads dependencies from the ns form; :as-alias libs are not loaded.
    (let [decl (read-string text)
          info (discover/ns-info "x.clj" text)]
      (and (= (ctn-parse/name-from-ns-decl decl) (:ns info))
           (= (ctn-parse/deps-from-ns-decl decl) (:requires info))))))

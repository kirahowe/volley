(ns brisk.gen.compat
  "Generators for the Kaocha-compatibility namespaces: cli, config, discover,
  select and deps-graph."
  (:require
   [brisk.select :as select]
   [clojure.string :as str]
   [clojure.test.check.generators :as gen]))

;; ---------------------------------------------------------------------------
;; Names

(def reserved-segments
  "Segments that read back as literals rather than symbols: a symbol named
  `nil` prints as `nil`, which the reader turns into nil."
  #{"nil" "true" "false"})

(def segment
  "A lowercase identifier segment such as `core` or `a2-b`. Never one of
  `reserved-segments`."
  (gen/such-that (complement reserved-segments)
                 (gen/fmap (fn [[c rest]] (apply str c rest))
                           (gen/tuple (gen/elements (seq "abcdefghijklmnopqrstuvwxyz"))
                                      (gen/vector (gen/elements (seq "abcdefghijklmnopqrstuvwxyz0123456789-")) 0 5)))
                 100))

(def ns-sym
  "A namespace symbol like `app.core`."
  (gen/fmap (fn [segs] (symbol (str/join "." segs))) (gen/vector segment 1 3)))

(def var-sym
  (gen/fmap symbol segment))

(def meta-key
  "Metadata keys that matter to filtering, plus a couple of bystanders."
  (gen/elements [:slow :integration :wip :kaocha/skip :kaocha/pending
                 :synchronized :brisk/serial :fast]))

(def meta-map
  "Literal metadata as it appears on a deftest or ns name."
  (gen/fmap (fn [ks] (zipmap ks (repeat true))) (gen/vector meta-key 0 3)))

;; ---------------------------------------------------------------------------
;; CLI

(def flag-token
  (gen/elements ["--focus" "--skip" "--focus-meta" "--skip-meta" "--no-color" "--color"
                 "--fail-fast" "--no-fail-fast" "--watch" "--no-watch" "-c" "--config-file"
                 "--reporter" "--plugin" "--workers" "--runtime" "--isolate" "--seed"
                 "--" "-H" "--help" "--test-help" "--bogus" "-x" "-" "--print-config"
                 "--print-test-plan" "--profile" "--diff-style" "--capture-output"
                 "--no-capture-output" "--version" "--focus=a" "--runtime=jvm"]))

(def value-token
  (gen/elements [":slow" "slow" "unit" "my.ns/foo" "my.ns-test" "3" "-5" "0" "" "jvm" "bb"
                 "tests.edn" "kaocha.report/dots" "documentation" ":ci"]))

(def argv-token
  (gen/one-of [flag-token value-token gen/string gen/string-alphanumeric
               (gen/fmap #(str "--" %) gen/string-alphanumeric)
               (gen/fmap #(str "-" %) gen/string-alphanumeric)]))

(def argv
  "Arbitrary command lines, biased towards real flags."
  (gen/vector argv-token 0 12))

;; ---------------------------------------------------------------------------
;; ns forms

(def ^:private require-style
  (gen/elements [:bare :as :refer :as-alias :prefix]))

(defn- lib-spec
  "Returns a generator of `{:form f :loads [sym] :aliased [sym]}`."
  [style]
  (gen/let [lib ns-sym
            prefix segment
            kids (gen/vector segment 1 3)
            alias segment]
    (let [alias (symbol alias)
          p (symbol prefix)]
      (case style
        :bare {:form lib :loads [lib] :aliased []}
        :as {:form [lib :as alias] :loads [lib] :aliased []}
        :refer {:form [lib :refer [alias]] :loads [lib] :aliased []}
        :as-alias {:form [lib :as-alias alias] :loads [] :aliased [lib]}
        :prefix (let [kids (distinct (map symbol kids))]
                  {:form (apply list p (map-indexed (fn [i k] (if (even? i) k [k :as alias])) kids))
                   :loads (mapv #(symbol (str p "." %)) kids)
                   :aliased []})))))

(def require-clause
  "`{:form (:require ...) :loads [...] :aliased [...]}`."
  (gen/let [kind (gen/elements [:require :use])
            specs (gen/vector (gen/bind require-style lib-spec) 0 4)]
    {:form (apply list kind (map :form specs))
     :loads (vec (mapcat :loads specs))
     :aliased (vec (mapcat :aliased specs))}))

(def deftest-head (gen/elements ['deftest 't/deftest 'clojure.test/deftest 'deftest- 'defspec]))

(defn- with-meta-printing [form]
  (binding [*print-meta* true] (pr-str form)))

(def ns-file
  "A generated test file. Returns
  `{:text str :ns sym :requires #{} :as-alias-only #{} :meta {} :vars {}}`
  where everything but `:text` is what `brisk.discover/ns-info` must report."
  (gen/let [nm ns-sym
            name-meta meta-map
            doc (gen/one-of [(gen/return nil) (gen/elements ["doc" "A test ns"])])
            attrs (gen/one-of [(gen/return nil) meta-map])
            clauses (gen/vector require-clause 0 3)
            tests (gen/vector (gen/tuple deftest-head var-sym meta-map) 0 4)]
    (let [loads (into #{} (mapcat :loads) clauses)
          aliased (into #{} (mapcat :aliased) clauses)
          tests (vals (into {} (map (fn [[h v m]] [v [h v m]])) tests))
          ns-form (concat ['ns (with-meta nm name-meta)]
                          (when doc [doc])
                          (when attrs [attrs])
                          (map :form clauses))
          test-forms (map (fn [[h v m]] (list h (with-meta v m) '(is true))) tests)]
      {:text (str/join "\n" (map with-meta-printing (cons (seq ns-form) test-forms)))
       :ns nm
       :requires loads
       :as-alias-only (into #{} (remove loads) aliased)
       :meta (cond-> (merge name-meta attrs) doc (assoc :doc doc))
       :vars (into {} (map (fn [[_ v m]] [v m])) tests)})))

;; ---------------------------------------------------------------------------
;; Corpus of namespace infos

(def ns-info
  "A static namespace info for a test namespace. `:hidden` holds the vars a
  macro defines, which exist at run time but which `brisk.discover` cannot
  see."
  (gen/let [nm ns-sym
            nm-meta meta-map
            vars (gen/map var-sym meta-map {:max-elements 4})
            hidden (gen/frequency [[3 (gen/return {})]
                                   [1 (gen/map (gen/fmap #(symbol (str "gen-" %)) var-sym)
                                               meta-map {:max-elements 2})]])]
    {:file (str "test/" (str/replace (str nm) "." "/") ".clj")
     :ns nm
     :requires #{}
     :as-alias-only #{}
     :meta nm-meta
     :vars vars
     :hidden hidden}))

(defn real-vars
  "Every test var of `info` at run time: the static ones and the hidden."
  [info]
  (merge (:vars info) (:hidden info)))

(defn worker-selected
  "Ids (`:ns/var` keywords) of the vars workers would run for `plan`: each
  planned namespace's real vars, decided with `brisk.select/decide` after
  the spec's focus-meta keys are settled against the real metadata of
  every namespace in the plan's `:preload`, as a worker does. Pending vars
  count as selected: Kaocha reports them instead of filtering them out."
  [plan infos]
  (let [by-ns (into {} (map (juxt :ns identity)) infos)
        used (select/used-meta (for [ns (:preload plan)
                                     :let [i (by-ns ns)]
                                     m (cons (:meta i) (vals (real-vars i)))]
                                 m))]
    (into #{}
          (for [{:keys [namespaces]} (:suites plan)
                {:keys [ns select]} namespaces
                :let [spec (:select (select/resolve-focus-meta select used))]
                [v m] (real-vars (by-ns ns))
                :when (#{:selected :pending} (:reason (select/decide spec ns v m)))]
            (keyword (str ns) (str v))))))

(def corpus
  "A vector of infos with distinct namespaces."
  (gen/fmap (fn [infos] (vec (vals (into {} (map (juxt :ns identity)) infos))))
            (gen/vector ns-info 1 5)))

(defn ids-of
  "Every suite-independent id a filter could name in `corpus`."
  [corpus]
  (concat (map #(keyword (str (:ns %))) corpus)
          (for [i corpus, v (keys (real-vars i))] (keyword (str (:ns i)) (str v)))))

(defn filter-gen
  "Generates `{:focus :skip :focus-meta :skip-meta}` naming things in `corpus`
  (and sometimes things that aren't there)."
  [corpus]
  (let [ids (vec (concat (ids-of corpus) [:nothing/here :unit :a :b]))
        id (gen/elements ids)]
    (gen/let [focus (gen/vector id 0 2)
              skip (gen/vector id 0 2)
              focus-meta (gen/vector meta-key 0 2)
              skip-meta (gen/vector meta-key 0 2)]
      {:focus focus :skip skip :focus-meta focus-meta :skip-meta skip-meta})))

(defn config-for
  "A normalized config with one suite, `:unit`, over test path \"test\"."
  [suite-filters global-filters]
  {:tests [(merge {:id :unit :type :kaocha.type/clojure.test :test-paths ["test"]
                   :source-paths ["src"] :ns-patterns ["."]
                   :focus [] :skip [] :focus-meta [] :skip-meta [:kaocha/skip]}
                  suite-filters)]
   :filters (merge {:focus [] :skip [] :focus-meta [] :skip-meta []} global-filters)})

;; ---------------------------------------------------------------------------
;; Config

(def suite-id (gen/elements [:unit :integration :slow-suite :other]))

(def path-str (gen/elements ["test" "test/unit" "src" "integration" "test/a"]))

(def hook-entry
  "A hook as tests.edn may give it: a symbol, a list form, or junk."
  (gen/elements ['my.hooks/setup! 'my.hooks/other '(fn [x] x) 'unqualified 42]))

(def raw-hooks
  "Hook keys of a config or suite, some unsupported, as Kaocha's hooks
  plugin reads them."
  (gen/map (gen/elements [:kaocha.hooks/pre-load :kaocha.hooks/pre-test :kaocha.hooks/post-test
                          :kaocha.hooks/before :kaocha.hooks/after :kaocha.hooks/pre-load-test
                          :kaocha.hooks/wrap-run :kaocha.hooks/post-summary :kaocha.hooks/config
                          :kaocha.hooks/no-such-hook :brisk/worker-setup])
           (gen/one-of [hook-entry (gen/vector hook-entry 0 3)])
           {:max-elements 3}))

(def raw-suite
  "A tests.edn suite with unqualified or Kaocha-qualified keys."
  (gen/let [qualified? gen/boolean
            id suite-id
            test-paths (gen/vector path-str 1 2)
            ns-patterns (gen/vector (gen/elements ["-test$" "^app\\." ".*"]) 1 2)
            skip-meta (gen/vector meta-key 0 2)
            focus (gen/vector (gen/elements ['app.core-test 'app.core-test/foo :app.x]) 0 2)
            with-keys (gen/vector (gen/elements [:paths :patterns :skip-meta :focus :hooks :skip]) 0 5)
            hooks raw-hooks]
    (let [ks (set with-keys)
          q (fn [unq qual] (if qualified? qual unq))]
      (cond-> {(q :id :kaocha.testable/id) id}
        (:paths ks) (assoc (q :test-paths :kaocha/test-paths) test-paths)
        (:patterns ks) (assoc (q :ns-patterns :kaocha/ns-patterns) ns-patterns)
        (:skip-meta ks) (assoc (q :skip-meta :kaocha.filter/skip-meta) skip-meta)
        (:focus ks) (assoc (q :focus :kaocha.filter/focus) focus)
        (:skip ks) (assoc :kaocha.testable/skip true)
        (:hooks ks) (merge hooks)))))

(def raw-config
  "Raw tests.edn data: nil, or a map with suites and global options."
  (gen/one-of
   [(gen/return nil)
    (gen/let [suites (gen/vector raw-suite 0 3)
              extras (gen/map (gen/elements [:fail-fast? :color? :watch? :capture-output?
                                             :randomize? :plugins :reporter :bindings :diff-style])
                              (gen/elements [true false])
                              {:max-elements 3})
              plugins (gen/vector (gen/elements [:randomize :notifier :kaocha.plugin/hooks
                                                 :kaocha.plugin/filter])
                                  0 2)
              reporter (gen/elements [nil ['kaocha.report/documentation] ['kaocha.report/dots]
                                      ['my.custom/reporter]])
              hooks raw-hooks]
      (cond-> (merge (dissoc extras :plugins :reporter :bindings :diff-style) hooks)
        (seq suites) (assoc :tests suites)
        (seq plugins) (assoc :plugins plugins)
        reporter (assoc :reporter reporter)))]))

(def cli-options
  "A parsed CLI options map (the `:options` of `brisk.cli/parse-args`)."
  (gen/let [fail-fast (gen/elements [nil true false])
            color (gen/elements [nil true false])
            watch (gen/elements [nil true false])
            capture-output (gen/elements [nil true false])
            workers (gen/elements [nil 1 3 16])
            isolate (gen/elements [nil true false])
            runtime (gen/elements [nil :bb :jvm])
            reporter (gen/elements [nil ['kaocha.report/documentation] ['kaocha.report/dots]])
            plugin (gen/elements [nil [:kaocha.plugin/notifier] [:kaocha.plugin/filter]])]
    (into {:config-file "tests.edn"}
          (remove (comp nil? val))
          {:fail-fast fail-fast :color color :watch watch :capture-output capture-output
           :workers workers :isolate isolate :runtime runtime :reporter reporter
           :plugin plugin})))

;; ---------------------------------------------------------------------------
;; Dependency graphs

(def graph-infos
  "Infos for namespaces n0..nK with arbitrary (possibly cyclic) requires and
  random test flags."
  (gen/let [n (gen/choose 1 7)
            edges (gen/vector (gen/vector (gen/choose 0 6) 0 3) n)
            tests (gen/vector gen/boolean n)]
    (vec (map-indexed (fn [i [reqs test?]]
                        {:file (str "src/n" i ".clj")
                         :ns (symbol (str "n" i))
                         :requires (into #{'clojure.test} (map #(symbol (str "n" %))) reqs)
                         :test? test?})
                      (map vector edges tests)))))

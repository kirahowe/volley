(ns volley.select
  "Which namespaces and vars run.

  The parent builds a plan from static namespace info (`plan`); each worker
  then decides per var, from real metadata, whether to run it
  (`volley.filters/decide`).
  Both sides follow `kaocha.plugin.filter` (`matches?`, `merge-filters`,
  `filter-testable`, `remove-missing-metadata-keys` and the `pre-load` and
  `post-load` hooks) so the same tests are picked as with Kaocha.

  Kaocha filters a test tree (suite, namespace, var) in two passes per suite:
  first with the suite's own `:focus`/`:skip` keys from tests.edn, then with
  the global filters (the CLI flags), which see the suite's skip keys too.
  `apply-filters` ports that over a static tree; `volley.filters/decide` is
  the closed form of the same rules for one var, which is what workers
  evaluate."
  (:require
   [volley.discover :as discover]
   [volley.filters :as filters]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Port of kaocha.plugin.filter over a tree of
;;   suite {:id kw :filters {...} :skip? b :children [ns ...]}
;;   ns    {:id kw :meta m :children [var ...]}
;;   var   {:id kw :meta m}
;; Every ns node has one child with :wild?, standing for tests we cannot see
;; statically (any macro call may define them). It matches a focus that
;; could name a var of its namespace, every focus-meta (their metadata is
;; unknown) and no skip.

(defn- wild-matches?
  "Could a test of the wildcard's namespace, invisible to static discovery,
  match? A focus id names it when it is the namespace or a var in it; its
  metadata is unknown, so any focus-meta might."
  [{:keys [ns]} focus focus-meta]
  (boolean
   (or (seq focus-meta)
       (some #(= ns (str %)) focus)
       (some #(= ns (namespace (keyword %))) focus))))

(defn- focus-matches? [node focus focus-meta]
  (if (:wild? node)
    (wild-matches? node focus focus-meta)
    (filters/matches? node focus focus-meta)))

(defn- test-seq
  "`kaocha.testable/test-seq`: the node and its descendants that aren't
  skipped (a skipped child hides its whole subtree)."
  [node]
  (cons node (mapcat test-seq (remove :skip? (:children node)))))

(defn- filter-node
  "`kaocha.plugin.filter/filter-testable`."
  [node opts]
  (let [{:keys [skip focus skip-meta focus-meta] :as opts} (filters/merge-filters opts (:filters node))
        recurse (fn [o]
                  (cond-> node
                    (:children node) (update :children (fn [cs] (mapv #(filter-node % o) cs)))))]
    (cond
      (or (seq focus) (seq focus-meta))
      (cond
        (:load-error? node) node
        (focus-matches? node focus focus-meta) (recurse (dissoc opts :focus :focus-meta))
        (some #(focus-matches? % focus focus-meta) (test-seq node)) (recurse opts)
        :else (assoc node :skip? true))

      (filters/matches? node skip skip-meta) (assoc node :skip? true)
      (:children node) (recurse opts)
      :else node)))

(defn- used-meta-keys
  "Metadata keys that are truthy on some test in the plan
  (`remove-missing-metadata-keys`)."
  [suites]
  (into #{}
        (comp (remove :skip?)
              (mapcat test-seq)
              (remove :wild?)
              (mapcat (fn [n] (keep (fn [[k v]] (when v (keyword k))) (:meta n)))))
        suites))

(defn- unresolved-focus-meta
  "Focus-meta keys no test the parser can see carries. Kaocha ignores a key
  that no loaded test carries, but tests defined by macros may carry it, so
  only a worker that has loaded every namespace can tell."
  [focus-meta used]
  (into [] (remove #(contains? used (keyword %))) focus-meta))

(defn- filter-suite
  "The `post-load` filtering of one suite: its own filters first, then the
  global ones. Returns the filtered suite with the `:passes` workers need
  and the focus-meta keys only workers can resolve."
  [suite global used]
  (let [own (:filters suite)
        stripped (assoc own :focus nil :focus-meta nil)
        filtered (-> suite
                     (filter-node {})
                     (assoc :filters stripped)
                     (filter-node global))]
    (assoc filtered
           :unresolved (unresolved-focus-meta (:focus-meta own) used)
           :passes (filters/suite-passes suite own global))))

(defn apply-filters
  "Run Kaocha's filter plugin over a static tree. `suites` are suite nodes
  (see above; `:skip?` may be preset, e.g. by suite ids on the command line);
  `global` is `{:focus :skip :focus-meta :skip-meta}`.

  Mirrors the `pre-load` hook (suite-level focus/skip, `pre-load`), then
  `post-load` (unmatched focus warning, two passes). Kaocha's focus-meta
  pruning needs every test's real metadata, so keys no visible test
  carries are returned as `:unresolved` for the workers to settle
  (`resolve-focus-meta`). Returns `{:suites [...filtered suite nodes with
  :skip? and :passes...] :loaded [suite-id] :unresolved [k] :warnings
  [str]}`, where `:loaded` are the suites Kaocha would load (the ones the
  `pre-load` step did not skip)."
  [suites global]
  (let [{:keys [focus skip focus-meta skip-meta]} global
        focus-suites (into #{} (comp (filter #(filters/matches? % focus focus-meta)) (map :id)) suites)
        pre (mapv (fn [s]
                    (if (and (not (:skip? s))
                             (or (filters/matches? s skip skip-meta)
                                 (and (seq focus-suites) (not (contains? focus-suites (:id s))))))
                      (assoc s :skip? true)
                      s))
                  suites)
        unmatched? (and (seq focus)
                        (not-any? #(focus-matches? % focus nil)
                                  (mapcat test-seq (remove :skip? pre))))
        used (used-meta-keys pre)
        out (mapv (fn [s] (if (:skip? s) s (filter-suite s global used))) pre)]
    {:suites out
     :loaded (into [] (comp (remove :skip?) (map :id)) pre)
     :unresolved (vec (distinct (concat (unresolved-focus-meta focus-meta used)
                                        (mapcat :unresolved (remove :skip? out)))))
     :warnings (vec (when unmatched? [(str ":focus " (pr-str (vec focus)) " did not match any tests.")]))}))

;; ---------------------------------------------------------------------------
;; Plan

(defn- serial? [{:keys [meta vars]}]
  (boolean (some #(or (get % :synchronized) (get % :volley/serial))
                 (cons meta (vals vars)))))

(defn- ->ns-node [{:keys [ns meta vars]}]
  {:id (keyword (str ns))
   :meta meta
   :children (conj (mapv (fn [[v m]] {:id (keyword (str ns) (str v)) :meta m}) vars)
                   {:id (keyword (str ns) "*generated*") :wild? true :ns (str ns)})})

(defn- suite-infos
  "Infos belonging to `suite`, one per namespace, ordered by name."
  [suite infos]
  (->> infos
       (filter #(discover/suite-member? suite %))
       (sort-by (juxt (comp str :ns) :file))
       (into [] (comp (remove :error) (remove #(nil? (:ns %)))
                      (partition-by :ns) (map first)))))

(defn- suite-node [suite infos]
  {:id (:id suite)
   :type (:type suite)
   :filters (select-keys suite [:focus :skip :focus-meta :skip-meta])
   :infos infos
   :children (if (= :kaocha.type/clojure.test (:type suite))
               (mapv ->ns-node infos)
               [])})

(defn- relevant-meta-keys [passes]
  (into #{:kaocha/pending}
        (comp (mapcat (juxt :focus-meta :skip-meta)) cat (map keyword))
        passes))

(defn- kept-namespaces
  "The ids of the namespaces of a filtered suite that may hold a selected
  test."
  [filtered]
  (into #{}
        (keep (fn [ns-node]
                (when (and (not (:skip? ns-node)) (some (complement :skip?) (:children ns-node)))
                  (:id ns-node))))
        (when-not (:skip? filtered) (:children filtered))))

(defn- plan-suite
  "The plan entry of one suite: `filtered` is the suite as Kaocha's
  filters leave it when every focus-meta key is carried by some test.
  Dropping a key no test carries never brings back a namespace this
  leaves out, so the namespaces kept here are the most any outcome needs.
  With `unresolved` keys each spec carries them and the `:pass-inputs`
  (`resolve-focus-meta`) a worker rebuilds its passes from. Each entry
  also keeps the namespace's static metadata and test vars (`:meta`,
  `:vars`) for `--print-test-plan`."
  [suite infos filtered global unresolved]
  (let [kept (kept-namespaces filtered)
        keys* (relevant-meta-keys (:passes filtered))]
    {:id (:id suite)
     :namespaces
     (into []
           (keep (fn [info]
                   (when (contains? kept (keyword (str (:ns info))))
                     {:ns (:ns info)
                      :file (:file info)
                      :serial? (serial? info)
                      :meta (:meta info)
                      :vars (:vars info)
                      :select (cond-> {:passes (:passes filtered)
                                       :ns-meta (select-keys (:meta info) keys*)}
                                (seq unresolved)
                                (assoc :resolve-focus-meta unresolved
                                       :pass-inputs {:suite (select-keys suite [:id :meta])
                                                     :own (:filters suite)
                                                     :global global}))})))
           infos)
     :load-errors []}))

(defn- load-errors [suite infos]
  (into []
        (comp (filter :error)
              (filter #(discover/suite-member? suite %))
              (map #(select-keys % [:file :error])))
        infos))

(defn- unknown-suite-error [unknown all]
  (str "No such suite: " (str/join ", " (sort unknown))
       ", valid options: " (str/join ", " (sort all)) "."))

(defn plan
  "Build the run plan.

  `config` is normalized config, `ns-infos` the `volley.discover/ns-info` of
  every source and test file, `cli-suites` the suite ids from the command
  line (empty means all). Returns

      {:suites [{:id :unit
                 :namespaces [{:ns 'my.app-test :file \"test/my/app_test.clj\"
                               :serial? false :select select-spec}]
                 :load-errors [{:file \"test/bad_test.clj\" :error \"...\"}]}]
       :warnings [str]
       :errors [str]}

  Suites not named on the command line are left out, as are namespaces
  that cannot hold a selected test. Namespaces are ordered by name.
  `:errors` is non-empty when a requested suite does not exist (Kaocha's
  \"No such suite\" message). `select-spec` is for `decide`.

  When a focus-meta key is carried by no test the parser can see, every
  spec gets `:resolve-focus-meta [k]` and the plan `:preload [{:ns sym
  :suite id}]`, every namespace of the run's suites with its suite: the workers load all of them before
  running anything and settle the keys with `resolve-focus-meta`."
  [config ns-infos cli-suites]
  (let [suites (:tests config)
        all-ids (into #{} (map :id) suites)
        wanted (set cli-suites)
        unknown (remove all-ids wanted)
        nodes (mapv (fn [s]
                      (cond-> (suite-node s (suite-infos s ns-infos))
                        (or (:skip? s) (and (seq wanted) (not (contains? wanted (:id s))))) (assoc :skip? true)))
                    suites)
        global (:filters config)
        {filtered :suites pre-loaded :loaded :keys [warnings unresolved]} (apply-filters nodes global)
        suites-by-id (zipmap (map :id suites) suites)]
    (cond->
     {:suites (vec (for [[node f] (map vector nodes filtered)
                         :when (and (not (:skip? f)) (= :kaocha.type/clojure.test (:type node)))]
                     (assoc (plan-suite node (:infos node) f global unresolved)
                            :load-errors (load-errors (suites-by-id (:id node)) ns-infos))))
      :warnings warnings
      :errors (if (seq unknown) [(unknown-suite-error unknown all-ids)] [])}
      (seq unresolved)
      (assoc :preload (into [] (comp (filter #(contains? (set pre-loaded) (:id %)))
                                     (filter #(= :kaocha.type/clojure.test (:type %)))
                                     (mapcat (fn [node] (map (fn [i] {:ns (:ns i) :suite (:id node)})
                                                             (:infos node)))))
                            nodes)))))

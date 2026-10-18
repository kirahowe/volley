(ns brisk.select
  "Which namespaces and vars run.

  The parent builds a plan from static namespace info (`plan`); each worker
  then decides per var, from real metadata, whether to run it (`decide`).
  Both sides follow `kaocha.plugin.filter` (`matches?`, `merge-filters`,
  `filter-testable`, `remove-missing-metadata-keys` and the `pre-load` and
  `post-load` hooks) so the same tests are picked as with Kaocha.

  Kaocha filters a test tree (suite, namespace, var) in two passes per suite:
  first with the suite's own `:focus`/`:skip` keys from tests.edn, then with
  the global filters (the CLI flags), which see the suite's skip keys too.
  `apply-filters` ports that over a static tree; `decide` is the closed form
  of the same rules for one var, which is what workers evaluate."
  (:require
   [brisk.discover :as discover]
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

(defn- matches?
  "`kaocha.plugin.filter/matches?` (without aliases, which brisk's test types
  never set). Ids are keywords: ns `:my.ns`, var `:my.ns/foo`, suite `:unit`.
  Note that, as in Kaocha, a keyword filter never matches a namespace by
  `(str filter)`; only symbols and strings do."
  [{:keys [id meta]} filters meta-filters]
  (boolean
   (or (some #(= (keyword %) id) filters)
       (some #(= (str %) (namespace id)) filters)
       (some #(get meta (keyword %)) meta-filters))))

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
    (matches? node focus focus-meta)))

(defn- merge-filters
  "`kaocha.plugin.filter/merge-filters`."
  [f1 f2]
  {:skip (concat (:skip f1) (:skip f2))
   :skip-meta (concat (:skip-meta f1) (:skip-meta f2))
   :focus (if (seq (:focus f2)) (:focus f2) (:focus f1))
   :focus-meta (if (seq (:focus-meta f2)) (:focus-meta f2) (:focus-meta f1))})

(defn- test-seq
  "`kaocha.testable/test-seq`: the node and its descendants that aren't
  skipped (a skipped child hides its whole subtree)."
  [node]
  (cons node (mapcat test-seq (remove :skip? (:children node)))))

(defn- filter-node
  "`kaocha.plugin.filter/filter-testable`."
  [node opts]
  (let [{:keys [skip focus skip-meta focus-meta] :as opts} (merge-filters opts (:filters node))
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

      (matches? node skip skip-meta) (assoc node :skip? true)
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

(defn missing-meta-warning
  "Kaocha's warning (`remove-missing-metadata-keys`) for a focus-meta key no
  test carries."
  [k]
  (str "No tests found with metadata key " k ". Ignoring --focus-meta " k "."))

(defn- unresolved-focus-meta
  "Focus-meta keys no test the parser can see carries. Kaocha ignores a key
  that no loaded test carries, but tests defined by macros may carry it, so
  only a worker that has loaded every namespace can tell."
  [focus-meta used]
  (into [] (remove #(contains? used (keyword %))) focus-meta))

(defn- pass-params
  "The filter options of one pass, as sent to workers."
  [{:keys [focus skip focus-meta skip-meta]} suite]
  {:focus (vec focus) :skip (vec skip) :focus-meta (vec focus-meta) :skip-meta (vec skip-meta)
   :suite-focused? (boolean (and (or (seq focus) (seq focus-meta))
                                 (matches? suite focus focus-meta)))})

(defn- empty-pass? [{:keys [focus skip focus-meta skip-meta]}]
  (not (or (seq focus) (seq skip) (seq focus-meta) (seq skip-meta))))

(defn- filter-suite
  "The `post-load` filtering of one suite: its own filters first, then the
  global ones. Returns the filtered suite with the `:passes` workers need
  and the focus-meta keys only workers can resolve."
  [suite global used]
  (let [own (:filters suite)
        suite1 (assoc suite :filters own)
        pass1 (merge-filters {} own)
        stripped (assoc own :focus nil :focus-meta nil)
        filtered (-> suite1
                     (filter-node {})
                     (assoc :filters stripped)
                     (filter-node global))
        pass2 (merge-filters global stripped)]
    (assoc filtered
           :unresolved (unresolved-focus-meta (:focus-meta own) used)
           :passes (into [] (remove empty-pass?) [(pass-params pass1 suite) (pass-params pass2 suite)]))))

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
        focus-suites (into #{} (comp (filter #(matches? % focus focus-meta)) (map :id)) suites)
        pre (mapv (fn [s]
                    (if (and (not (:skip? s))
                             (or (matches? s skip skip-meta)
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
;; Worker side

(defn- skip-reason
  "Why `node` is skipped by `skip`/`skip-meta`: `:skip` when an id matched,
  `:skip-meta` when metadata did, nil when it is not skipped."
  [node skip skip-meta]
  (cond
    (matches? node skip nil) :skip
    (matches? node nil skip-meta) :skip-meta))

(defn- decide-pass
  "The closed form of one `filter-node` pass for a single var. Returns nil
  to run it or a reason keyword."
  [{:keys [focus skip focus-meta skip-meta suite-focused?]} ns-node var-node]
  (let [skip-ns #(skip-reason ns-node skip skip-meta)
        skip-var #(skip-reason var-node skip skip-meta)]
    (if (or (seq focus) (seq focus-meta))
      (cond
        suite-focused? (or (skip-ns) (skip-var))
        (matches? ns-node focus focus-meta) (skip-var)
        (matches? var-node focus focus-meta) nil
        :else :not-focused)
      (or (skip-ns) (skip-var)))))

(defn- var-pattern-reason
  "Watch mode's test-name filter: `:not-focused` unless the var's name
  matches the spec's `:var-pattern` (a regex string, compiled once by
  `prepare` into `:var-re`). No pattern selects every var."
  [{:keys [var-pattern var-re]} var-sym]
  (when-let [re (or var-re (some-> var-pattern re-pattern))]
    (when-not (re-find re (str var-sym))
      :not-focused)))

(defn prepare
  "The select spec ready for deciding many vars: its `:var-pattern`
  compiled once (as `:var-re`), rather than once per var. Workers call it
  once per namespace; the spec that crosses the process boundary stays
  EDN."
  [select-spec]
  (cond-> select-spec
    (and (:var-pattern select-spec) (not (:var-re select-spec)))
    (assoc :var-re (re-pattern (:var-pattern select-spec)))))

(defn decide
  "Decide whether a test var runs. `select-spec` is the `:select` value from
  `plan`; `var-meta` is the var's real metadata and the namespace metadata
  comes from the spec's static copy (the 5-arity takes the namespace's real
  metadata instead). Returns `{:run? bool :reason kw}` where reason is
  `:selected`, `:skip`, `:skip-meta`, `:not-focused` or `:pending`. A
  `:var-pattern` in the spec (watch mode's test-name filter) makes vars
  whose names do not match it `:not-focused`.

  Skips are decided before `:kaocha/pending`, as in `kaocha.testable/run-testable`."
  ([select-spec ns-sym var-sym var-meta]
   (decide select-spec ns-sym (:ns-meta select-spec) var-sym var-meta))
  ([select-spec ns-sym ns-meta var-sym var-meta]
   (let [ns-node {:id (keyword (str ns-sym)) :meta ns-meta}
         var-node {:id (keyword (str ns-sym) (str var-sym)) :meta var-meta}
         reason (or (when (:excluded? select-spec) :not-focused)
                    (some #(decide-pass % ns-node var-node) (:passes select-spec))
                    (var-pattern-reason select-spec var-sym))]
     (cond
       reason {:run? false :reason reason}
       (or (get var-meta :kaocha/pending) (get ns-meta :kaocha/pending))
       {:run? false :reason :pending}
       :else {:run? true :reason :selected}))))

(defn used-meta
  "Metadata keys that are truthy in any of `metas` (namespace and var
  metadata), as keywords, like `kaocha.plugin.filter/truthy-keys`."
  [metas]
  (into #{} (comp (mapcat seq) (keep (fn [[k v]] (when v (keyword k))))) metas))

(defn- variant-key [ks] (vec (sort-by str (distinct (map keyword ks)))))

(defn resolve-focus-meta
  "Settle the select spec's `:resolve-focus-meta` keys once the worker has
  loaded every namespace of the run: keys in `used` (see `used-meta`) stay,
  the others are dropped, as Kaocha's `remove-missing-metadata-keys` drops
  them, and the spec takes the passes the plan computed for that case
  (`:excluded?` when the namespace has no test to run then). Returns
  `{:select spec :dropped [k]}`; a spec with nothing to resolve comes back
  unchanged."
  [select-spec used]
  (let [pending (:resolve-focus-meta select-spec)
        dropped (vec (remove #(contains? used (keyword %)) pending))
        choice (get (:variants select-spec) (variant-key dropped))
        spec (dissoc select-spec :resolve-focus-meta :variants)]
    {:select (cond
               (empty? pending) spec
               (or (nil? choice) (= :excluded choice)) (assoc spec :passes [] :excluded? true)
               :else (-> spec (assoc :passes choice) (dissoc :excluded?)))
     :dropped dropped}))

;; ---------------------------------------------------------------------------
;; Plan

(defn- serial? [{:keys [meta vars]}]
  (boolean (some #(or (get % :synchronized) (get % :brisk/serial))
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
  "`{ns-id passes}` for the namespaces of a filtered suite that may hold a
  selected test."
  [filtered]
  (into {}
        (keep (fn [ns-node]
                (when (and (not (:skip? ns-node)) (some (complement :skip?) (:children ns-node)))
                  [(:id ns-node) (:passes filtered)])))
        (when-not (:skip? filtered) (:children filtered))))

(defn- plan-suite
  "The plan entry of one suite. `variants` maps each set of focus-meta keys
  that may turn out to be carried by no test (sorted keyword vectors; `[]`
  when they all are) to the suite as filtered under that assumption. A
  namespace is planned when some variant keeps it; its spec carries the
  passes of every variant (`:excluded` where it is dropped) when there is
  more than one."
  [suite infos variants unresolved]
  (let [kept (update-vals variants kept-namespaces)
        base-passes (:passes (get variants []))
        keys* (relevant-meta-keys base-passes)]
    {:id (:id suite)
     :namespaces
     (into []
           (keep (fn [info]
                   (let [id (keyword (str (:ns info)))
                         per-variant (update-vals kept #(get % id :excluded))]
                     (when (some #(not= :excluded %) (vals per-variant))
                       {:ns (:ns info)
                        :file (:file info)
                        :serial? (serial? info)
                        :select (cond-> {:passes (let [p (get per-variant [])] (if (= :excluded p) [] p))
                                         :ns-meta (select-keys (:meta info) keys*)}
                                  (= :excluded (get per-variant [])) (assoc :excluded? true)
                                  (seq unresolved) (assoc :resolve-focus-meta unresolved
                                                          :variants per-variant))}))))
           infos)
     :load-errors []}))

(defn- load-errors [suite infos]
  (into []
        (comp (filter :error)
              (filter #(discover/suite-member? suite %))
              (map #(select-keys % [:file :error])))
        infos))

(defn- subsets
  "Every subset of `xs`, as vectors, the empty one first."
  [xs]
  (reduce (fn [acc x] (into acc (map #(conj % x)) acc)) [[]] xs))

(defn- without-focus-meta
  "`filters` without the focus-meta keys in `dropped` (keywords)."
  [filters dropped]
  (update filters :focus-meta (fn [fm] (filterv #(not (contains? dropped (keyword %))) fm))))

(defn- unknown-suite-error [unknown all]
  (str "No such suite: " (str/join ", " (sort unknown))
       ", valid options: " (str/join ", " (sort all)) "."))

(defn plan
  "Build the run plan.

  `config` is normalized config, `ns-infos` the `brisk.discover/ns-info` of
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
        ;; With focus-meta keys only workers can settle, filter once per
        ;; set of keys that may turn out unused (Kaocha drops those).
        variants (into {[] filtered}
                       (map (fn [ks]
                              (let [d (set ks)]
                                [(variant-key ks)
                                 (:suites (apply-filters (mapv #(update % :filters without-focus-meta d) nodes)
                                                         (without-focus-meta global d)))])))
                       (rest (subsets (map keyword unresolved))))
        suites-by-id (zipmap (map :id suites) suites)
        loaded (filter (fn [i] (some #(not (:skip? (nth % i))) (vals variants)))
                       (range (count nodes)))]
    (cond->
     {:suites (vec (for [i loaded
                         :let [node (nth nodes i)]
                         :when (= :kaocha.type/clojure.test (:type node))]
                     (assoc (plan-suite node (:infos node) (update-vals variants #(nth % i)) unresolved)
                            :load-errors (load-errors (suites-by-id (:id node)) ns-infos))))
      :warnings warnings
      :errors (if (seq unknown) [(unknown-suite-error unknown all-ids)] [])}
      (seq unresolved)
      (assoc :preload (into [] (comp (filter #(contains? (set pre-loaded) (:id %)))
                                     (filter #(= :kaocha.type/clojure.test (:type %)))
                                     (mapcat (fn [node] (map (fn [i] {:ns (:ns i) :suite (:id node)})
                                                             (:infos node)))))
                            nodes)))))

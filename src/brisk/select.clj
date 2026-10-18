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
;; A node with :wild? stands for tests we cannot see statically; it matches
;; every focus and no skip.

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

(defn- focus-matches? [node focus focus-meta]
  (or (:wild? node) (matches? node focus focus-meta)))

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

(defn- prune-focus-meta
  "Returns `[kept warnings]`: focus-meta keys that no test carries are
  dropped with a warning, as Kaocha does."
  [focus-meta used]
  (let [kept (filterv #(contains? used (keyword %)) focus-meta)
        dropped (remove (set kept) focus-meta)]
    [kept (mapv #(str "No tests found with metadata key " % ". Ignoring --focus-meta " % ".") dropped)]))

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
  global ones. Returns the filtered suite with the `:passes` workers need."
  [suite global used]
  (let [own (:filters suite)
        [own-fm own-warnings] (prune-focus-meta (:focus-meta own) used)
        own (assoc own :focus-meta own-fm)
        suite1 (assoc suite :filters own)
        pass1 (merge-filters {} own)
        stripped (assoc own :focus nil :focus-meta nil)
        filtered (-> suite1
                     (filter-node {})
                     (assoc :filters stripped)
                     (filter-node global))
        pass2 (merge-filters global stripped)]
    (assoc filtered
           :warnings own-warnings
           :passes (into [] (comp (remove empty-pass?)) [(pass-params pass1 suite) (pass-params pass2 suite)]))))

(defn apply-filters
  "Run Kaocha's filter plugin over a static tree. `suites` are suite nodes
  (see above; `:skip?` may be preset, e.g. by suite ids on the command line);
  `global` is `{:focus :skip :focus-meta :skip-meta}`.

  Mirrors the `pre-load` hook (suite-level focus/skip, `pre-load`), then
  `post-load` (unmatched focus warning, focus-meta pruning, two passes).
  Returns `{:suites [...filtered suite nodes with :skip? and :passes...]
  :warnings [str]}`."
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
                        (not-any? #(matches? % focus nil)
                                  (mapcat test-seq (remove :skip? pre))))
        used (used-meta-keys pre)
        [global-fm global-warnings] (prune-focus-meta focus-meta used)
        global (assoc global :focus-meta global-fm)
        out (mapv (fn [s] (if (:skip? s) s (filter-suite s global used))) pre)]
    {:suites out
     :warnings (vec (concat (when unmatched? [(str ":focus " (pr-str (vec focus)) " did not match any tests.")])
                            global-warnings
                            (mapcat :warnings out)))}))

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

(defn decide
  "Decide whether a test var runs. `select-spec` is the `:select` value from
  `plan`; `var-meta` is the var's real metadata and the namespace metadata
  comes from the spec's static copy (the 5-arity takes the namespace's real
  metadata instead). Returns `{:run? bool :reason kw}` where reason is
  `:selected`, `:skip`, `:skip-meta`, `:not-focused` or `:pending`.

  Skips are decided before `:kaocha/pending`, as in `kaocha.testable/run-testable`."
  ([select-spec ns-sym var-sym var-meta]
   (decide select-spec ns-sym (:ns-meta select-spec) var-sym var-meta))
  ([select-spec ns-sym ns-meta var-sym var-meta]
   (let [ns-node {:id (keyword (str ns-sym)) :meta ns-meta}
         var-node {:id (keyword (str ns-sym) (str var-sym)) :meta var-meta}
         reason (some #(decide-pass % ns-node var-node) (:passes select-spec))]
     (cond
       reason {:run? false :reason reason}
       (or (get var-meta :kaocha/pending) (get ns-meta :kaocha/pending))
       {:run? false :reason :pending}
       :else {:run? true :reason :selected}))))

;; ---------------------------------------------------------------------------
;; Plan

(defn- serial? [{:keys [meta vars]}]
  (boolean (some #(or (get % :synchronized) (get % :brisk/serial))
                 (cons meta (vals vars)))))

(defn- ->ns-node [{:keys [ns meta vars]}]
  {:id (keyword (str ns))
   :meta meta
   :children (if (seq vars)
               (mapv (fn [[v m]] {:id (keyword (str ns) (str v)) :meta m}) vars)
               [{:id :wild :wild? true}])})

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

(defn- plan-suite [filtered]
  (let [passes (:passes filtered)
        keys* (relevant-meta-keys passes)
        by-ns (zipmap (map (comp keyword str :ns) (:infos filtered)) (:infos filtered))]
    {:id (:id filtered)
     :namespaces
     (into []
           (keep (fn [ns-node]
                   (let [info (get by-ns (:id ns-node))]
                     (when (and (not (:skip? ns-node)) (some (complement :skip?) (:children ns-node)))
                       {:ns (:ns info)
                        :file (:file info)
                        :serial? (serial? info)
                        :select {:passes passes
                                 :ns-meta (select-keys (:meta info) keys*)}})))
                 (:children filtered)))
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

  `config` is normalized config, `ns-infos` the `brisk.discover/ns-info` of
  every source and test file, `cli-suites` the suite ids from the command
  line (empty means all). Returns

      {:suites [{:id :unit
                 :namespaces [{:ns 'my.app-test :file \"test/my/app_test.clj\"
                               :serial? false :select select-spec}]
                 :load-errors [{:file \"test/bad_test.clj\" :error \"...\"}]}]
       :warnings [str]
       :errors [str]}

  Suites not named on the command line are left out, as are namespaces with
  no test left after filtering. Namespaces are ordered by name. `:errors` is
  non-empty when a requested suite does not exist (Kaocha's \"No such
  suite\" message). `select-spec` is for `decide`."
  [config ns-infos cli-suites]
  (let [suites (:tests config)
        all-ids (into #{} (map :id) suites)
        wanted (set cli-suites)
        unknown (remove all-ids wanted)
        nodes (mapv (fn [s]
                      (cond-> (suite-node s (suite-infos s ns-infos))
                        (and (seq wanted) (not (contains? wanted (:id s)))) (assoc :skip? true)))
                    suites)
        {filtered :suites :keys [warnings]} (apply-filters nodes (:filters config))
        suites-by-id (zipmap (map :id suites) suites)]
    {:suites (into []
                   (comp (remove :skip?)
                         (filter #(= :kaocha.type/clojure.test (:type %)))
                         (map #(assoc (plan-suite %) :load-errors
                                      (load-errors (suites-by-id (:id %)) ns-infos))))
                   filtered)
     :warnings warnings
     :errors (if (seq unknown) [(unknown-suite-error unknown all-ids)] [])}))

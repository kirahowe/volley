(ns volley.filters
  "Kaocha's filter rules (`kaocha.plugin.filter`) for one var at a time:
  what a worker needs to decide which vars run. `volley.select` builds the
  plan with them in the parent. Kept apart from `volley.select`, which
  reads source files with edamame, so that a worker loads none of that.")

(defn matches?
  "`kaocha.plugin.filter/matches?` (without aliases, which volley's test types
  never set). Ids are keywords: ns `:my.ns`, var `:my.ns/foo`, suite `:unit`.
  Note that, as in Kaocha, a keyword filter never matches a namespace by
  `(str filter)`; only symbols and strings do."
  [{:keys [id meta]} filters meta-filters]
  (boolean
   (or (some #(= (keyword %) id) filters)
       (some #(= (str %) (namespace id)) filters)
       (some #(get meta (keyword %)) meta-filters))))

(defn merge-filters
  "`kaocha.plugin.filter/merge-filters`."
  [f1 f2]
  {:skip (concat (:skip f1) (:skip f2))
   :skip-meta (concat (:skip-meta f1) (:skip-meta f2))
   :focus (if (seq (:focus f2)) (:focus f2) (:focus f1))
   :focus-meta (if (seq (:focus-meta f2)) (:focus-meta f2) (:focus-meta f1))})

(defn- empty-pass? [{:keys [focus skip focus-meta skip-meta]}]
  (not (or (seq focus) (seq skip) (seq focus-meta) (seq skip-meta))))

(defn- pass-params
  "The filter options of one pass, as sent to workers. `:suite-skipped?`
  is true when the pass skips the whole suite (`filter-node` checks a
  skip against the suite only when the pass has no focus); a plan never
  holds such a suite, but passes a worker rebuilds after dropping
  focus-meta keys can (`resolve-focus-meta`)."
  [{:keys [focus skip focus-meta skip-meta]} suite]
  (let [focus? (or (seq focus) (seq focus-meta))]
    (cond-> {:focus (vec focus) :skip (vec skip) :focus-meta (vec focus-meta) :skip-meta (vec skip-meta)
             :suite-focused? (boolean (and focus? (matches? suite focus focus-meta)))}
      (and (not focus?) (matches? suite skip skip-meta)) (assoc :suite-skipped? true))))

(defn suite-passes
  "The passes workers apply for `suite` with its own filters `own` and the
  global ones: the suite's filters first, then the global ones, which see
  the suite's skip keys too."
  [suite own global]
  (let [pass1 (merge-filters {} own)
        pass2 (merge-filters global (assoc own :focus nil :focus-meta nil))]
    (into [] (remove empty-pass?) [(pass-params pass1 suite) (pass-params pass2 suite)])))

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
  [{:keys [focus skip focus-meta skip-meta suite-focused? suite-skipped?]} ns-node var-node]
  (let [skip-ns #(skip-reason ns-node skip skip-meta)
        skip-var #(skip-reason var-node skip skip-meta)]
    (cond
      suite-skipped? :skip
      (or (seq focus) (seq focus-meta))
      (cond
        suite-focused? (or (skip-ns) (skip-var))
        (matches? ns-node focus focus-meta) (skip-var)
        (matches? var-node focus focus-meta) nil
        :else :not-focused)
      :else (or (skip-ns) (skip-var)))))

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
         reason (or (some #(decide-pass % ns-node var-node) (:passes select-spec))
                    (var-pattern-reason select-spec var-sym))]
     (cond
       reason {:run? false :reason reason}
       (or (get var-meta :kaocha/pending) (get ns-meta :kaocha/pending))
       {:run? false :reason :pending}
       :else {:run? true :reason :selected}))))

(defn runs-namespace?
  "Whether a namespace with metadata `ns-meta` and vars split by
  `volley.shell.worker/partition-vars` (`{:run [var ...] :skipped [[var
  reason] ...]}`) is run at all: its hooks and `:once` fixtures around the
  vars in `run`. `kaocha.testable/run-testable` runs a namespace
  unless it is pending itself or every test in it is skipped, and a
  pending var is not skipped, so a namespace whose vars are all pending
  still runs its `:once` fixtures."
  [ns-meta {:keys [run skipped]}]
  (boolean (and (not (:kaocha/pending ns-meta))
                (or (seq run) (some (fn [[_ reason]] (= :pending reason)) skipped)))))

(defn used-meta
  "Metadata keys that are truthy in any of `metas` (namespace and var
  metadata), as keywords, like `kaocha.plugin.filter/truthy-keys`."
  [metas]
  (into #{} (comp (mapcat seq) (keep (fn [[k v]] (when v (keyword k))))) metas))

(defn missing-meta-warning
  "Kaocha's warning (`remove-missing-metadata-keys`) for a focus-meta key no
  test carries."
  [k]
  (str "No tests found with metadata key " k ". Ignoring --focus-meta " k "."))

(defn without-focus-meta
  "`filters` without the focus-meta keys in `dropped` (keywords)."
  [filters dropped]
  (update filters :focus-meta (fn [fm] (filterv #(not (contains? dropped (keyword %))) fm))))

(defn resolve-focus-meta
  "Settle the select spec's `:resolve-focus-meta` keys once the worker has
  loaded every namespace of the run: keys in `used` (see `used-meta`) stay,
  the others are dropped, as Kaocha's `remove-missing-metadata-keys` drops
  them from the suite's filters and the global ones, and the spec's passes
  are rebuilt from its `:pass-inputs` without them. Returns `{:select spec
  :dropped [k]}`; a spec with nothing to resolve comes back unchanged."
  [select-spec used]
  (let [pending (:resolve-focus-meta select-spec)
        dropped (vec (remove #(contains? used (keyword %)) pending))
        {:keys [suite own global]} (:pass-inputs select-spec)
        d (set (map keyword dropped))
        spec (dissoc select-spec :resolve-focus-meta :pass-inputs)]
    {:select (if (empty? dropped)
               spec
               (assoc spec :passes (suite-passes suite (without-focus-meta own d)
                                                 (without-focus-meta global d))))
     :dropped dropped}))

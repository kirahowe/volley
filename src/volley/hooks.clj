(ns volley.hooks
  "Kaocha's hooks plugin and volley's `:volley/worker-setup`, as data.

  Kaocha runs every hook in its one process. volley has a parent that plans
  and reports and workers that load and run namespaces, so each hook runs
  where its work happens. `semantics` is the table, and doc/design.md
  explains it. This namespace is pure: it reads the hook configuration out
  of the Kaocha config, builds the `{:op :configure}` message for workers,
  and builds the Kaocha-shaped maps hooks receive (testables, the test plan,
  the result). Hooks are resolved and called by `volley.shell.worker` and,
  for `post-summary`, `volley.main`."
  (:require
   [volley.filters :as filters]
   [volley.run-state :as rs]
   [volley.testable :as testable]))

(def semantics
  "Where and when each `:kaocha.hooks/*` key runs in volley."
  {:pre-load       {:where :worker :when "once per worker, before its first namespace is required"}
   :post-load      {:where :worker :when "after each namespace the worker loads"}
   :pre-run        {:where :worker :when "once per worker, before the tests of its first namespace"}
   :post-run       {:where :worker :when "once per worker, when it is shut down cleanly"}
   :wrap-run       {:where :worker :when "around the run of each namespace and each var"}
   :pre-test       {:where :worker :when "before each namespace and each var; a suite's own: once per worker"}
   :post-test      {:where :worker :when "after each var and each namespace; a suite's own: at shutdown"}
   :pre-load-test  {:where :worker :when "before each namespace is required; for the suite once per worker"}
   :post-load-test {:where :worker :when "after each namespace is required; for the suite once per worker"}
   :pre-report     {:where :worker :when "on every clojure.test report event, before volley counts it"}
   :post-summary   {:where :parent :when "once, after the summary is printed"}})

(def top-level-keys
  "Hook keys Kaocha accepts at the top of tests.edn."
  (set (keys semantics)))

(def suite-keys
  "Hook keys Kaocha accepts on a suite (`kaocha.plugin.hooks`,
  `load-testable-level-hooks`)."
  #{:pre-test :post-test :pre-load-test :post-load-test})

(def plugin
  "The plugin that turns Kaocha's hooks on."
  :kaocha.plugin/hooks)

(defn hook-key?
  "True for a `:kaocha.hooks/...` key."
  [k]
  (and (keyword? k) (= "kaocha.hooks" (namespace k))))

(defn- ->vec
  "Kaocha's `load-hooks`: a vector is a list of hooks, anything else (a
  symbol, or one list form) is one hook."
  [x]
  (cond (nil? x) [] (vector? x) x :else [x]))

(defn- entry-ok?
  "A hook is a qualified symbol naming a fn, or a list form that evaluates
  to one (Kaocha's `load-hook`)."
  [x]
  (or (qualified-symbol? x) (seq? x)))

(defn- read-entries
  "`[entries warnings]` for the value of hook key `k`."
  [k v where]
  (let [xs (->vec v)
        bad (remove entry-ok? xs)]
    [(filterv entry-ok? xs)
     (for [b bad]
       (str "hook " (pr-str b) " under " (pr-str k) where
            " is not a qualified symbol or a list form, ignored"))]))

(def ^:private aliases
  "Kaocha's `rename-aliases`: `before` and `after` are added to `pre-test`
  and `post-test`."
  {:before :pre-test :after :post-test})

(defn read-hooks
  "The hooks in map `m` (the top-level config or one suite). `allowed` is
  the set of supported short keys; `where` is text for warnings such as
  \" (suite :unit)\". Returns `{:hooks {short-key [entry ...]} :warnings
  [str]}`, only non-empty vectors kept."
  [m allowed where]
  (let [entries (->> (keys m)
                     (filter hook-key?)
                     ;; aliases after their targets, as rename-aliases appends them
                     (sort-by (fn [k] [(contains? aliases (keyword (name k))) (str k)])))]
    (reduce (fn [acc k]
              (let [short (keyword (name k))
                    target (get aliases short short)]
                (cond
                  (= :config short)
                  (update acc :warnings conj
                          (str (pr-str k) where " is not supported by volley (it cannot change the plan), ignored"))
                  (not (contains? allowed target))
                  (update acc :warnings conj (str "unsupported hook " (pr-str k) where " ignored"))
                  :else
                  (let [[es ws] (read-entries k (get m k) where)]
                    (cond-> (update acc :warnings into ws)
                      (seq es) (update-in [:hooks target] (fnil into []) es))))))
            {:hooks {} :warnings []}
            entries)))

(defn read-worker-setup
  "`[entries warnings]` for `:volley/worker-setup` in `m`."
  [m where]
  (read-entries :volley/worker-setup (:volley/worker-setup m) where))

(defn hook-keys
  "The `:kaocha.hooks/...` keys of `m`."
  [m]
  (filter hook-key? (keys m)))

;; ---------------------------------------------------------------------------
;; Kaocha-shaped values

(defn suite-testable
  "The Kaocha testable of normalized suite `suite`, as hooks see it, with
  the suite's own filters under Kaocha's `:kaocha.filter/*` keys."
  [{:keys [id type test-paths source-paths ns-patterns focus skip focus-meta skip-meta]}]
  (cond-> {:kaocha.testable/type (or type :kaocha.type/clojure.test)
           :kaocha.testable/id id
           :kaocha.testable/desc (str (name id) " (clojure.test)")
           :kaocha/test-paths test-paths
           :kaocha/source-paths source-paths
           :kaocha/ns-patterns ns-patterns}
    (seq focus) (assoc :kaocha.filter/focus focus)
    (seq skip) (assoc :kaocha.filter/skip skip)
    (seq focus-meta) (assoc :kaocha.filter/focus-meta focus-meta)
    (seq skip-meta) (assoc :kaocha.filter/skip-meta skip-meta)))

(defn- kaocha-hook-keys [hooks]
  (into {} (map (fn [[k v]] [(keyword "kaocha.hooks" (name k)) v])) hooks))

(defn kaocha-config
  "A Kaocha-shaped config for normalized `config`, which hooks get where
  Kaocha passes its config or test plan."
  [config]
  (merge {:kaocha/tests (mapv #(merge (suite-testable %) (kaocha-hook-keys (:hooks %))) (:tests config))
          :kaocha/plugins (:plugins config)
          :kaocha/fail-fast? (boolean (:fail-fast? config))
          :kaocha/color? (boolean (:color? config))
          :kaocha.plugin.capture-output/capture-output? (boolean (:capture-output? config))
          :kaocha.plugin.randomize/randomize? (boolean (:randomize? config))}
         (when (:seed config) {:kaocha.plugin.randomize/seed (:seed config)})
         (kaocha-hook-keys (:hooks config))))

(defn configure-message
  "The `{:op :configure}` message every worker gets before its first run,
  or nil when no hook, worker setup or custom reporter is configured.
  `:reporters` are the project's own reporter symbols (`--reporter`,
  `:kaocha/reporter`), which the workers call with each clojure.test
  event."
  [config]
  (let [suites (into {}
                     (keep (fn [s]
                             (when (or (seq (:hooks s)) (seq (:worker-setup s)))
                               [(:id s) (select-keys s [:hooks :worker-setup])])))
                     (:tests config))
        worker-hooks (dissoc (:hooks config) :post-summary)]
    (when (or (seq worker-hooks) (seq (:worker-setup config)) (seq suites) (seq (:reporters config)))
      {:op :configure
       :hooks worker-hooks
       :reporters (vec (:reporters config))
       :worker-setup (vec (:worker-setup config))
       :suites suites
       :testables (into {} (map (fn [s] [(:id s) (suite-testable s)])) (:tests config))
       :config (kaocha-config config)})))

(defn kaocha-test-plan
  "`--print-test-plan`: Kaocha's test plan shape (`kaocha.api/test-plan`)
  for normalized `config` and `plan` (`volley.select/plan`), suites holding
  the planned namespaces holding the test vars the parser saw. A var the
  filters skip carries `:kaocha.testable/skip`, as in Kaocha; tests a
  macro defines appear only once loaded, so not here. volley's own part of
  the plan is under `:volley/*` keys: each suite's filter passes once, each
  namespace's `:volley/serial?`, and the namespaces workers preload."
  [config plan]
  (let [by-id (into {} (map (juxt :id identity)) (:tests config))
        var-t (fn [{:keys [ns select]} [v m]]
                (cond-> (testable/var-testable ns v m)
                  (not (#{:selected :pending} (:reason (filters/decide select ns v m))))
                  (assoc :kaocha.testable/skip true)))
        ns-t (fn [{:keys [ns meta vars serial?] :as entry}]
               (cond-> (assoc (testable/ns-testable ns)
                              :kaocha.testable/meta meta
                              :kaocha.test-plan/tests (mapv #(var-t entry %) (sort-by (comp str key) vars)))
                 serial? (assoc :volley/serial? true)))]
    (cond-> (testable/test-plan
             (kaocha-config config)
             (for [{:keys [id namespaces]} (:suites plan)]
               (cond-> (assoc (suite-testable (by-id id))
                              :kaocha.test-plan/tests (mapv ns-t namespaces))
                 (seq namespaces) (assoc :volley/passes (get-in (first namespaces) [:select :passes])))))
      (seq (:preload plan)) (assoc :volley/preload (:preload plan)))))

(defn kaocha-result
  "The test result `post-summary` hooks get: Kaocha's shape
  (`:kaocha.result/tests`, suites holding namespaces holding vars with
  `:kaocha.result/count`, `pass`, `fail`, `error` and `pending`; vars
  skipped by a filter carry `:kaocha.testable/skip` and no counts) built
  from volley's run state. `suite-of` maps each namespace to its suite id."
  [config suite-of run]
  (let [var-result (fn [ns v]
                     (let [t (testable/var-testable ns (:var v) {})]
                       (cond
                         (= :pending (:reason v)) (testable/with-result t {:pending 1})
                         (= :skipped (rs/var-status v)) (assoc t :kaocha.testable/skip true)
                         :else (testable/with-result t (rs/var-counts v)))))
        ns-result (fn [[ns rec]]
                    (assoc (testable/ns-testable ns)
                           :kaocha.result/tests (mapv #(var-result ns %) (vals (:vars rec)))))
        by-suite (group-by (comp suite-of key) (:namespaces run))]
    (testable/test-plan (kaocha-config config)
               (for [s (:tests config)]
                 (assoc (suite-testable s)
                        :kaocha.result/tests (mapv ns-result (sort-by (comp str key) (get by-suite (:id s))))))
               :kaocha.result/tests)))

(defn engine-opts
  "The hook options of `volley.engine/init` for normalized `config`:
  `:configure` (the message for workers) and `:post-summary` (the parent's
  hooks), each nil when not configured."
  [config]
  {:configure (configure-message config)
   :post-summary (when-let [hs (seq (get-in config [:hooks :post-summary]))]
                   {:hooks (vec hs) :config config})})

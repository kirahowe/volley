(ns volley.config
  "tests.edn text and CLI options to volley's normalized config.

  Reading follows Aero as Kaocha uses it: `#profile`, `#env`, `#envf`,
  `#prop`, `#long`, `#double`, `#keyword`, `#boolean`, `#include`, `#join`,
  `#read-edn`, `#merge`, `#ref`, `#hostname`, `#user` and `#or` are Aero's
  readers; `#kaocha/v1`, `#kaocha` and `#meta-merge` are the ones Kaocha adds
  (`kaocha.config`). Anything environmental (env vars, system properties,
  included files) is passed in, never read here.

  Normalizing follows `kaocha.config/normalize`, `merge-config` and
  `apply-cli-opts` (see the docstrings of the private helpers for the Kaocha
  function each one mirrors)."
  (:require
   [volley.discover :as discover]
   [volley.hooks :as hooks]
   [volley.runtime :as runtime]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; meta-merge (port of meta-merge.core/meta-merge, which Kaocha uses)

(defn- meta* [obj]
  (when (instance? clojure.lang.IObj obj) (meta obj)))

(defn- with-meta* [obj m]
  (if (instance? clojure.lang.IObj obj) (with-meta obj m) obj))

(defn- displace? [obj] (:displace (meta* obj)))
(defn- replace? [obj] (:replace (meta* obj)))
(defn- top-displace? [obj] (:top-displace (meta* obj)))

(defn- different-priority? [left right]
  (boolean
   (or (some (some-fn nil? displace? replace?) [left right])
       (top-displace? left))))

(defn- remove-top-displace [obj]
  (if (top-displace? obj) (vary-meta obj dissoc :top-displace) obj))

(defn- pick-prioritized [left right]
  (cond
    (nil? left) right
    (nil? right) (remove-top-displace left)
    (top-displace? left) right
    (and (displace? left) (displace? right))
    (with-meta* right (merge (meta* left) (meta* right)))
    (and (replace? left) (replace? right))
    (with-meta* right (merge (meta* left) (meta* right)))
    (or (displace? left) (replace? right))
    (with-meta* right (merge (dissoc (meta* left) :displace) (dissoc (meta* right) :replace)))
    (or (replace? left) (displace? right))
    (with-meta* left (merge (dissoc (meta* right) :displace) (dissoc (meta* left) :replace)))))

(defn meta-merge
  "Recursively merge values based on `:replace`, `:displace`, `:prepend` and
  `:append` metadata, exactly like `meta-merge.core/meta-merge`."
  ([] {})
  ([left] left)
  ([left right]
   (cond
     (different-priority? left right) (pick-prioritized left right)
     (and (map? left) (map? right)) (merge-with meta-merge left right)
     (and (set? left) (set? right)) (into right left)
     (and (coll? left) (coll? right))
     (if (or (:prepend (meta left)) (:prepend (meta right)))
       (-> (into (empty left) (concat right left))
           (with-meta (merge (meta left) (select-keys (meta right) [:displace]))))
       (into (empty left) (concat left right)))
     :else right))
  ([left right & more]
   (reduce meta-merge left (cons right more))))

;; ---------------------------------------------------------------------------
;; Kaocha defaults and normalization

(def kaocha-defaults
  "The value of `kaocha/default_config.edn` in Kaocha 1.91.1392."
  {:kaocha/reporter ['kaocha.report/dots]
   :kaocha/color? true
   :kaocha/fail-fast? false
   :kaocha/plugins [:kaocha.plugin/randomize
                    :kaocha.plugin/filter
                    :kaocha.plugin/capture-output]
   :kaocha/tests [{:kaocha.testable/type :kaocha.type/clojure.test
                   :kaocha.testable/id :unit
                   :kaocha/ns-patterns ["-test$"]
                   :kaocha/source-paths ["src"]
                   :kaocha/test-paths ["test"]
                   :kaocha.filter/skip-meta [:kaocha/skip]}]})

(defn- config-error [msg data]
  (ex-info msg (assoc data :volley/config-error true)))

(defn- rename-key [m old-key new-key]
  (if (contains? m old-key)
    (assoc (dissoc m old-key) new-key (get m old-key))
    m))

(defn- replace-by-default
  "Mirrors `kaocha.config/replace-by-default`: values under the given key
  replace the default instead of being appended, unless they say otherwise."
  [config k]
  (if-let [v (get config k)]
    (cond
      (some #{:prepend :append} (keys (meta v))) config
      (or (coll? v) (symbol? v)) (update config k vary-meta assoc :replace true)
      :else (throw (config-error
                    (str "Test suite configuration value with key " k
                         " should be a collection or symbol, but got '" (pr-str v) "'")
                    {:key k :value v})))
    config))

(defn merge-config
  "Mirrors `kaocha.config/merge-config`: `meta-merge` where tests, reporter,
  test-paths, source-paths and ns-patterns replace rather than concatenate."
  [c1 c2]
  (meta-merge c1 (reduce replace-by-default c2
                         [:kaocha/reporter :kaocha/tests :kaocha/test-paths
                          :kaocha/source-paths :kaocha/ns-patterns])))

(defn- normalize-test-suite
  "Mirrors `kaocha.config/normalize-test-suite`."
  [m]
  (let [m (-> m
              (rename-key :type :kaocha.testable/type)
              (rename-key :id :kaocha.testable/id)
              (rename-key :test-paths :kaocha/test-paths)
              (rename-key :source-paths :kaocha/source-paths)
              (rename-key :ns-patterns :kaocha/ns-patterns)
              (rename-key :skip :kaocha.filter/skip)
              (rename-key :focus :kaocha.filter/focus)
              (rename-key :skip-meta :kaocha.filter/skip-meta)
              (rename-key :focus-meta :kaocha.filter/focus-meta)
              (rename-key :skip? :kaocha.testable/skip))]
    (merge-config (first (:kaocha/tests kaocha-defaults)) m)))

(defn- kaocha-normalize
  "Mirrors `kaocha.config/normalize` (renames unqualified keys, normalizes
  each suite against the default suite). Unlike Kaocha it tolerates a volley
  reporter keyword so already-normalized configs can be normalized again."
  [config]
  (let [{:keys [tests plugins reporter color? fail-fast? diff-style randomize?
                capture-output? watch? bindings]} config
        tests (some->> (or tests (:kaocha/tests config)) (mapv normalize-test-suite))
        kaocha-reporter? (and reporter (not (keyword? reporter)))]
    (cond-> {}
      tests (assoc :kaocha/tests (vary-meta tests assoc :replace true))
      plugins (assoc :kaocha/plugins plugins)
      kaocha-reporter? (assoc :kaocha/reporter (vary-meta reporter assoc :replace true))
      bindings (assoc :kaocha/bindings bindings)
      (some? color?) (assoc :kaocha/color? color?)
      (some? fail-fast?) (assoc :kaocha/fail-fast? fail-fast?)
      (some? diff-style) (assoc :kaocha/diff-style diff-style)
      (some? watch?) (assoc :kaocha/watch? watch?)
      (some? randomize?) (assoc :kaocha.plugin.randomize/randomize? randomize?)
      (some? capture-output?) (assoc :kaocha.plugin.capture-output/capture-output? capture-output?)
      true (merge (dissoc config :tests :plugins :reporter :color? :fail-fast? :watch? :randomize?)
                  (when tests {:kaocha/tests (vary-meta tests assoc :replace true)})))))

;; ---------------------------------------------------------------------------
;; Reading (Aero + Kaocha readers)

(declare resolve-form)

(defn- pending?
  "True when `form` still contains an unresolved tagged literal."
  [form]
  (boolean (some tagged-literal? (tree-seq coll? seq form))))

(defn- rebuild
  "Apply `f` to the elements of collection `form`, keeping its type and meta."
  [form f]
  (let [out (cond
              (map? form) (into (empty form) (map (fn [[k v]] [(f k) (f v)])) form)
              (vector? form) (into (empty form) (map f) form)
              (set? form) (into (empty form) (map f) form)
              (seq? form) (apply list (map f form))
              :else form)]
    (if (and (coll? form) (meta form)) (with-meta out (meta form)) out)))

(defn- expand-set-keys
  "Aero lets `#{:a :b} v` stand for both `:a v` and `:b v` in case maps."
  [m]
  (into {} (mapcat (fn [[k v]] (if (set? k) (map #(vector % v) k) [[k v]]))) m))

(defn- resolve-case
  "`#profile`, `#hostname` and `#user`: pick a branch by `value`, else `:default`."
  [ctx tag form value]
  (when-not (map? form)
    (throw (config-error (str "#" tag " expects a map") {:tag tag})))
  (let [m (expand-set-keys form)]
    (resolve-form ctx (get m value (get m :default)))))

(defn- resolve-or
  [ctx form]
  (let [vs (mapv #(resolve-form ctx %) form)]
    (if (some pending? vs)
      (tagged-literal 'or vs)
      (first (filter identity vs)))))

(defn- resolve-ref
  [ctx form]
  (let [path (resolve-form ctx form)
        v (when-not (pending? path) (get-in (:root ctx) path ::absent))]
    (if (or (pending? path) (= ::absent v) (pending? v))
      (tagged-literal 'ref path)
      v)))

(declare read-source)

(defn- resolve-include
  [{:keys [opts source depth] :as ctx} form]
  (let [path (resolve-form ctx form)]
    (if (pending? path)
      (tagged-literal 'include path)
      (let [text (when-let [f (:include opts)] (f path source))]
        (if text
          (read-source text opts path (inc depth))
          {:aero/missing-include path})))))

(defn- parse-double* [s]
  (Double/parseDouble s))

(defn- apply-tag
  "Apply the reader for `tag` to its fully resolved `value`."
  [{:keys [opts]} tag value]
  (let [env (:env opts)]
    (case tag
      env (get env (str value))
      envf (let [[fmt & args] value]
             (apply format fmt (map #(str (get env (str %))) args)))
      prop (get (:props opts) (str value))
      long (or (parse-long (str value))
               (throw (config-error (str "#long cannot parse " (pr-str value)) {})))
      double (parse-double* (str value))
      keyword (if (keyword? value) value (keyword (str value)))
      boolean (= "true" (str/lower-case (str value)))
      join (apply str value)
      read-edn (some-> value str edn/read-string)
      merge (apply merge value)
      (kaocha/v1 kaocha) value
      meta-merge (apply meta-merge value)
      (throw (config-error (str "No reader for tag " tag) {:tag tag})))))

(defn- resolve-tag
  [{:keys [opts] :as ctx} tl]
  (let [{:keys [tag form]} tl]
    (case tag
      ref (resolve-ref ctx form)
      include (resolve-include ctx form)
      profile (resolve-case ctx tag form (:profile opts))
      hostname (resolve-case ctx tag form (:hostname opts))
      user (resolve-case ctx tag form (or (:user opts) (get (:env opts) "USER")))
      or (resolve-or ctx form)
      (let [v (resolve-form ctx form)]
        (if (pending? v)
          (tagged-literal tag v)
          (apply-tag ctx tag v))))))

(defn- resolve-form [ctx form]
  (cond
    (tagged-literal? form) (resolve-tag ctx form)
    (coll? form) (rebuild form #(resolve-form ctx %))
    :else form))

(defn- drop-refs
  "Aero replaces references that never resolve with nil."
  [form]
  (cond
    (and (tagged-literal? form) (= 'ref (:tag form))) nil
    (tagged-literal? form) (tagged-literal (:tag form) (drop-refs (:form form)))
    (coll? form) (rebuild form drop-refs)
    :else form))

(defn- settle
  "Resolve tags repeatedly so `#ref` can point at values that themselves
  needed resolving."
  [ctx form]
  (loop [root form n 0]
    (let [out (resolve-form (assoc ctx :root root) root)]
      (cond
        (not (pending? out)) out
        (or (= out root) (> n 20)) (drop-refs out)
        :else (recur out (inc n))))))

(defn- read-source [text opts source depth]
  (when (> depth 20)
    (throw (config-error "#include nesting is too deep" {:source source})))
  (let [form (edn/read-string {:default tagged-literal :eof nil} text)]
    (settle {:opts opts :source source :depth depth} form)))

(defn- deprecated-tags
  "Warnings for the `#kaocha` literal, which Kaocha deprecates in favour of
  `#kaocha/v1`."
  [form]
  (when (some #(and (tagged-literal? %) (= 'kaocha (:tag %)))
              (tree-seq #(or (coll? %) (tagged-literal? %))
                        #(if (tagged-literal? %) [(:form %)] (seq %))
                        form))
    ["The #kaocha reader literal is deprecated, please change it to kaocha/v1."]))

(defn- default-profile [{:keys [profile env]}]
  (or profile (if (= "true" (get env "CI")) :ci :default)))

(defn read-config
  "Parse tests.edn `text`. Returns `{:config raw :warnings [...]}` or
  `{:error \"message\"}`; never throws.

  `opts` (all optional):
  - `:profile`  the `#profile` to pick. Default `:ci` when env CI is \"true\",
                else `:default` (as `kaocha.config/read-config-source`).
  - `:env`      map of environment variables (string keys) for `#env`/`#envf`.
  - `:props`    map of system properties for `#prop`.
  - `:hostname` and `:user` for `#hostname` and `#user`.
  - `:include`  `(fn [path including-source])` returning the text of an
                included file, or nil when it does not exist (Aero then
                yields `{:aero/missing-include path}`).

  The result is the raw value, tags resolved. `#kaocha/v1` is the identity
  here; `normalize` applies Kaocha's defaults exactly once. Blank text gives a
  nil config (Kaocha's \"use the defaults\")."
  ([text] (read-config text {}))
  ([text opts]
   (try
     (let [opts (assoc opts :profile (default-profile opts))
           form (edn/read-string {:default tagged-literal :eof nil} (or text ""))]
       {:config (settle {:opts opts :source nil :depth 0} form)
        :warnings (vec (deprecated-tags form))})
     (catch Exception e
       {:error (str "Failed reading tests.edn: " (or (ex-message e) (str e)))}))))

;; ---------------------------------------------------------------------------
;; Normalization to volley's config

(def ^:private supported-plugins
  "Plugins volley implements or stands in for: filtering, capture,
  hooks and junit-xml are built in; profiling is volley's slowest-tests
  list and print-invocations its rerun command. Randomize shuffles the
  vars of each namespace (`volley.order`)."
  #{:kaocha.plugin/filter :kaocha.plugin/capture-output :kaocha.plugin/randomize
    :kaocha.plugin/print-invocations :kaocha.plugin/profiling :kaocha.plugin/hooks
    :kaocha.plugin/junit-xml})

(def ^:private ignored-plugins
  "Plugins that ship with Kaocha (or, for cloverage and junit-xml's
  siblings, are common add-ons) that volley does not run. Listing them is
  accepted, with their options, and one warning names them all."
  #{:kaocha.plugin/notifier :kaocha.plugin/gc-profiling :kaocha.plugin/debug
    :kaocha.plugin/orchestra :kaocha.plugin/preloads :kaocha.plugin/version-filter
    :kaocha.plugin.alpha/info :kaocha.plugin.alpha/spec-test-check :kaocha.plugin.alpha/xfail
    :kaocha.plugin/cloverage})

(defn- plugin-key-namespaces
  "Namespaces of the config keys of `plugins`, e.g. `kaocha.plugin.notifier`
  for `:kaocha.plugin/notifier`; cloverage also reads `:cloverage/*`."
  [plugins]
  (into #{} (mapcat (fn [p] (cond-> [(str (namespace p) "." (name p))]
                              (= :kaocha.plugin/cloverage p) (conj "cloverage"))))
        plugins))

(defn plugin-candidates
  "The namespaces Kaocha tries to load for `plugin`
  (`kaocha.plugin/try-load-third-party-lib`): `a.b.c` and then `a.b` for
  `:a.b/c`, the name itself for a simple keyword."
  [plugin]
  (if (qualified-keyword? plugin)
    [(symbol (str (namespace plugin) "." (name plugin))) (symbol (namespace plugin))]
    [(symbol (name plugin))]))

(defn- subforms
  "Every form nested in `forms`, leaving out `(comment ...)` blocks."
  [forms]
  (mapcat #(tree-seq (fn [f] (and (coll? f) (not (and (seq? f) (= 'comment (first f)))))) seq %)
          forms))

(defn- head-is? [nm form]
  (and (seq? form) (symbol? (first form)) (= nm (name (first form)))))

(defn- defines-plugin?
  "True when `forms` define `plugin`: a `(defplugin name ...)` of its name,
  or a `(defmethod -register kw ...)` of its keyword, as Kaocha's
  `defplugin` expands to."
  [plugin forms]
  (let [sym (symbol (subs (str plugin) 1))]
    (boolean (some #(or (and (head-is? "defplugin" %) (= sym (second %)))
                        (and (head-is? "defmethod" %) (symbol? (second %))
                             (= "-register" (name (second %))) (= plugin (nth % 2 nil))))
                   (subforms forms)))))

(defn- wraps-defplugin?
  "True when `forms` define a macro that mentions `defplugin`, which may
  define plugins under another name."
  [forms]
  (boolean (some #(and (head-is? "defmacro" %)
                       (some (fn [f] (and (symbol? f) (= "defplugin" (name f)))) (subforms [%])))
                 (subforms forms))))

(defn- library-ns?
  "Namespaces that define no third-party plugin, so the search skips them."
  [ns-sym]
  (some #(str/starts-with? (str ns-sym) %) ["clojure." "kaocha."]))

(defn- plugin-search
  "Search namespaces `start` and, transitively, what they require for a
  definition of `plugin`. Returns :defined, :unsure when one may hide in
  something unread (a namespace without source, a source that does not
  parse, a macro wrapping `defplugin`), or nil when there is none."
  [plugin source-of start]
  (loop [queue (seq start) seen (set start) unsure? false]
    (if-let [[ns-sym & more] queue]
      (if-let [text (source-of ns-sym)]
        (let [{:keys [forms error]} (discover/read-forms text ns-sym)
              reqs (remove (some-fn seen library-ns?) (:requires (discover/ns-info nil text)))]
          (if (defines-plugin? plugin forms)
            :defined
            (recur (seq (concat more reqs)) (into seen reqs) (or unsure? (some? error) (wraps-defplugin? forms)))))
        (recur more seen true))
      (when unsure? :unsure))))

(defn plugin-load-error
  "Kaocha's error for `plugin` (`kaocha.plugin/register`), or nil when it
  loads. `source-of` returns a namespace's source text, nil when it cannot
  be loaded. Kaocha tries `a.b` only when `a.b.c` fails
  (`try-load-third-party-lib`). Returns `{:error :plugin-error :message s}`
  (exit 248) when a namespace it tried failed and none defines the plugin,
  `{:error :plugin-undefined :message s}` (exit 249) when they all loaded.
  The source is read, never loaded: a definition counts in a tried
  namespace or anything it requires, and when one may hide where volley
  cannot see (see `plugin-search`) the plugin is taken to load.
  `kaocha.plugin` itself, always there under Kaocha, defines no other plugin."
  [plugin source-of]
  (let [source #(if (= 'kaocha.plugin %) "" (source-of %))
        sources (map (juxt identity source) (plugin-candidates plugin))
        tried (if (second (first sources)) (take 1 sources) sources)
        failed (map first (remove second tried))]
    (cond
      (plugin-search plugin source (map first (filter second tried)))
      nil

      (empty? failed)
      {:error :plugin-undefined
       :message (str "Couldn't load plugin " plugin ". The plugin was not defined after loading namespace "
                     (ffirst tried) ". Is the file missing a defplugin?")}

      :else
      {:error :plugin-error
       :message (str "Couldn't load plugin " plugin ". Failed to load namespace" (when (next failed) "s") " "
                     (str/join " and " failed)
                     ". This could be caused by a misspelling or a missing dependency.")})))

(def ^:private silent-namespaces
  #{"kaocha.plugin.profiling" "kaocha.plugin.print-invocations"
    "kaocha.plugin.randomize" "kaocha.plugin.capture-output" "kaocha.filter"
    "kaocha.plugin.junit-xml"})

(def ^:private known-keys
  #{:kaocha/tests :kaocha/plugins :kaocha/reporter :kaocha/color? :kaocha/fail-fast?
    :kaocha/watch? :kaocha/cli-options :kaocha/cli-args
    ;; unqualified leftovers that kaocha-normalize copies through
    :capture-output? :diff-style :bindings :kaocha/bindings
    ;; volley's own shape, so a normalized config can be normalized again
    :workers :isolate? :preload? :seed :runtime :warnings :errors :filters :reporter :reporters
    :hooks :worker-setup :junit-xml :unknown-plugins :invalid
    ;; read by volley.hooks
    :volley/worker-setup})

(def ^:private known-suite-keys
  #{:kaocha.testable/id :kaocha.testable/type :kaocha.testable/desc
    :kaocha/test-paths :kaocha/source-paths :kaocha/ns-patterns
    :kaocha.filter/skip :kaocha.filter/focus :kaocha.filter/skip-meta
    :kaocha.filter/focus-meta :kaocha.testable/skip :skip?})

(defn- silent-key? [plugin-namespaces k]
  (or (contains? known-keys k)
      (hooks/hook-key? k)
      (and (qualified-keyword? k) (or (contains? silent-namespaces (namespace k))
                                      (contains? plugin-namespaces (namespace k))))))

(defn- ->vec
  "Coerce a config value to a vector. Scalars become one-element vectors."
  [x]
  (cond (nil? x) [] (sequential? x) (vec x) (set? x) (vec x) :else [x]))

(defn- normalize-plugin
  "Mirrors `kaocha.plugin/normalize-name` (what Kaocha applies when loading
  plugins): a simple keyword without a period lives in `kaocha.plugin`.
  Non-keywords are dropped instead of throwing."
  [p]
  (cond
    (not (keyword? p)) nil
    (and (simple-keyword? p) (not (str/includes? (name p) "."))) (keyword "kaocha.plugin" (name p))
    :else p))

(defn- suite-view [suite]
  (let [{:kaocha.testable/keys [id type] :kaocha/keys [test-paths source-paths ns-patterns]
         :kaocha.filter/keys [skip focus skip-meta focus-meta]} suite]
    (cond->
     {:id id
      :type type
      :test-paths (->vec test-paths)
      :source-paths (->vec source-paths)
      :ns-patterns (->vec ns-patterns)
      :focus (->vec focus)
      :skip (->vec skip)
      :focus-meta (->vec focus-meta)
      :skip-meta (->vec (distinct (->vec skip-meta)))}
      ;; kaocha.testable/run-testable never runs a suite marked skipped,
      ;; even one named on the command line
      (:kaocha.testable/skip suite) (assoc :skip? true))))

(defn- suite-warnings [suite]
  (let [id (:kaocha.testable/id suite)
        type (:kaocha.testable/type suite)]
    (concat
     (when (not= :kaocha.type/clojure.test type)
       [(str "suite " (pr-str id) " has unsupported type " (pr-str type) ", skipped")])
     (for [k (sort-by str (keys suite))
           :when (not (or (contains? known-suite-keys k)
                          (hooks/hook-key? k)
                          (contains? #{:hooks :worker-setup :volley/worker-setup} k)))]
       (str "unsupported suite option " (pr-str k) " ignored (suite " (pr-str id) ")")))))

(defn- read-hook-config
  "Hooks and worker setup of `m` (the config or a suite) in volley's shape,
  `{:hooks {k [entry]} :worker-setup [entry] :warnings [str]}`. Kaocha's
  `:kaocha.hooks/*` keys count only when the hooks plugin is on; volley's own
  `:hooks` and `:worker-setup` (an already normalized config) are kept."
  [m allowed where hooks-on?]
  (let [{read :hooks ws :warnings} (hooks/read-hooks m allowed where)
        [setup setup-ws] (hooks/read-worker-setup m where)]
    {:hooks (merge (when (map? (:hooks m)) (:hooks m)) (when hooks-on? read))
     :worker-setup (into (->vec (:worker-setup m)) setup)
     :warnings (concat (when hooks-on? ws) setup-ws)}))

(defn- hooks-off-warning [cfg suites hooks-on?]
  (let [ks (sort-by str (distinct (mapcat hooks/hook-keys (cons cfg suites))))]
    (when (and (not hooks-on?) (seq ks))
      [(str "hooks " (str/join ", " (map pr-str ks)) " ignored: "
            (pr-str hooks/plugin) " is not in :plugins")])))

(defn kaocha-shaped
  "`--print-config`: normalized `config` in Kaocha's shape
  (`kaocha.config/normalize`, as `volley.hooks/kaocha-config` builds it
  for hooks) with Kaocha's top-level settings, and volley's own settings
  under `:volley/*` keys."
  [config]
  (cond-> (merge (hooks/kaocha-config config)
                 {:kaocha/watch? (boolean (:watch? config))
                  :volley/reporter (:reporter config)
                  :volley/workers (:workers config)
                  :volley/isolate? (boolean (:isolate? config))
                  :volley/preload? (boolean (:preload? config))
                  :volley/runtime (:runtime config)})
    (seq (remove empty? (vals (:filters config)))) (assoc :volley/filters (:filters config))
    (seq (:bindings config)) (assoc :kaocha/bindings (:bindings config))
    (seq (:reporters config)) (assoc :kaocha/reporter (:reporters config))
    (seq (:worker-setup config)) (assoc :volley/worker-setup (:worker-setup config))
    (:junit-xml config) (assoc :volley/junit-xml (:junit-xml config))))

(defn- with-hook-config
  "Add `:hooks` and `:worker-setup` to `m` when they are not empty."
  [m {:keys [hooks worker-setup]}]
  (cond-> m
    (seq hooks) (assoc :hooks hooks)
    (seq worker-setup) (assoc :worker-setup worker-setup)))

(def ^:private builtin-reporters
  "Kaocha's own reporters and the volley output each stands for. volley
  prints its own output for them without a warning, since its output has
  its own layout whichever reporter is named."
  {'kaocha.report/documentation :documentation
   'kaocha.report/dots :live
   'kaocha.report/result :live
   'kaocha.report.progress/report :live
   'kaocha.report/report :live
   'clojure.test/report :live})

(def ^:private unsupported-reporters
  "Kaocha reporters with no volley equivalent: a warning, then volley's
  output."
  #{'kaocha.report/tap 'kaocha.report/debug})

(defn- volley-reporter
  "Map explicitly requested Kaocha reporter symbols to volley's output.
  Returns `[reporter warnings custom]`: the volley reporter keyword
  (`:documentation` for `kaocha.report/documentation`, else `:live`, which
  the shell turns into `:ci` when stdout is not a terminal), warnings for
  Kaocha reporters volley has no equivalent for, and every other symbol, a
  reporter of the project's own that the workers call with each
  clojure.test event. nil (nothing requested) is `:live`."
  [reporter]
  (if (or (nil? reporter) (keyword? reporter))
    [(or reporter :live) [] []]
    (let [syms (->vec reporter)
          builtin (keep builtin-reporters syms)
          unsupported (filter unsupported-reporters syms)]
      [(or (first (remove #{:live} builtin)) :live)
       (mapv #(str "reporter " (pr-str %) " has no volley equivalent, using volley's own output") unsupported)
       (vec (remove #(or (contains? builtin-reporters %) (unsupported-reporters %)) syms))])))

(defn- cli-filters [options]
  (into {}
        (keep (fn [k] (when (seq (get options k)) [k (->vec (get options k))])))
        [:focus :skip :focus-meta :skip-meta]))

(defn- config-filters
  "Global filters: volley's `:filters`, else Kaocha's top-level
  `:kaocha.filter/*` keys, with the CLI taking precedence key by key (the
  `kaocha.plugin.filter/config` hook)."
  [cfg cli-options]
  (let [from-config (merge (into {} (map (fn [k] [k (->vec (get cfg (keyword "kaocha.filter" (name k))))]))
                                 [:focus :skip :focus-meta :skip-meta])
                           (when (map? (:filters cfg)) (update-vals (:filters cfg) ->vec)))]
    (merge {:focus [] :skip [] :focus-meta [] :skip-meta []}
           from-config
           (cli-filters cli-options))))

(defn- pick
  "First of the candidates that is not nil (false counts)."
  [& xs]
  (first (filter some? xs)))

(defn- randomize
  "`{:randomize? b :seed n}` as `kaocha.plugin.randomize/config` settles
  them: on by default with the plugin, `--[no-]randomize` deciding, and
  the seed from `--seed`, tests.edn, or the shell's random `env-seed`.
  `:seed` is left out when the order is not randomized or no seed is
  known."
  [cfg raw cli-options plugins env-seed]
  (let [on? (boolean (pick (:randomize cli-options)
                           (when (some #{:kaocha.plugin/randomize} plugins)
                             (pick (:kaocha.plugin.randomize/randomize? cfg) true))
                           false))
        seed (when on?
               (pick (:seed cli-options) (:kaocha.plugin.randomize/seed cfg)
                     (:seed raw) env-seed))]
    (cond-> {:randomize? on?}
      (int? seed) (assoc :seed seed))))

(defn- spec-errors
  "What Kaocha's config spec rejects (\"Invalid configuration file\", exit
  252) that volley checks: a suite id that is not a keyword."
  [suites]
  (vec (for [s suites
             :let [id (:kaocha.testable/id s)]
             :when (not (keyword? id))]
         (str "suite id " (pr-str id) " should be a keyword (:kaocha.testable/id)"))))

(defn- volley-value-errors
  "volley's own tests.edn keys with values it cannot use. Like the values
  Kaocha rejects, they stop the run (exit 250) rather than being guessed
  at."
  [raw]
  (cond-> []
    (and (contains? raw :preload?) (not (boolean? (:preload? raw))))
    (conj (str ":preload? must be true or false, got " (pr-str (:preload? raw))))
    (and (contains? raw :isolate?) (not (boolean? (:isolate? raw))))
    (conj (str ":isolate? must be true or false, got " (pr-str (:isolate? raw))))
    ;; Kaocha's kaocha.config/binding-map fails on anything but a map
    (some #(and (contains? raw %) (not (map? (get raw %)))) [:bindings :kaocha/bindings])
    (conj (str ":kaocha/bindings must be a map of var symbols to values, got "
               (pr-str (or (:kaocha/bindings raw) (:bindings raw)))))
    (and (contains? raw :workers) (not (pos-int? (:workers raw))))
    (conj (str ":workers must be a positive integer, got " (pr-str (:workers raw))))
    (and (contains? raw :runtime) (not (contains? #{:bb :jvm} (:runtime raw))))
    (conj (str ":runtime must be :bb or :jvm, got " (pr-str (:runtime raw))))))

(defn- junit-xml
  "`kaocha.plugin.junit-xml`'s settings: the target file from
  `--junit-xml-file` or `:kaocha.plugin.junit-xml/target-file`, and the two
  flags. nil when the plugin is off or no file is named, as the plugin then
  writes nothing."
  [cfg raw cli-options plugins]
  (let [target (pick (:junit-xml-file cli-options)
                     (:kaocha.plugin.junit-xml/target-file cfg)
                     (get-in raw [:junit-xml :target-file]))]
    (when (and target (contains? (set plugins) :kaocha.plugin/junit-xml))
      {:target-file (str target)
       :omit-system-out? (boolean (pick (:junit-xml-omit-system-out cli-options)
                                        (:kaocha.plugin.junit-xml/omit-system-out? cfg)
                                        (get-in raw [:junit-xml :omit-system-out?])))
       :add-location? (boolean (pick (:junit-xml-add-location-metadata cli-options)
                                     (:kaocha.plugin.junit-xml/add-location-metadata? cfg)
                                     (get-in raw [:junit-xml :add-location?])))})))


(defn normalize
  "Normalize raw `tests.edn` data (from `read-config`, or nil when the file is
  missing) and parsed CLI `cli-options` (the `:options` of
  `volley.cli/parse-args`) into volley's config; see doc/design.md.

  Applies Kaocha's `#kaocha/v1` defaults and `normalize`, then the CLI
  overrides of `kaocha.config/apply-cli-opts`; the CLI always wins. Accepts
  both `:test-paths` and `:kaocha/test-paths` style keys. Unsupported plugins
  and options become `:warnings` entries. `env` may carry `:seed`, the
  random seed to use when neither `--seed` nor tests.edn gives one,
  `:cpus` for the default worker count and `:bb?` (default true) for the
  runtime of the parent, which is also the default worker runtime.

  Total: input Kaocha would reject (where it exits with an error, such as a
  string for `:test-paths`) yields the defaults and a message in `:errors`,
  which the caller should report and exit on. A non-map config yields the
  defaults and a warning."
  ([raw cli-options] (normalize raw cli-options {}))
  ([raw cli-options env]
   (let [bad? (and (some? raw) (not (map? raw)))
         raw (if (map? raw) raw {})
         cli-options (or cli-options {})
         cfg (try (merge-config kaocha-defaults (kaocha-normalize raw))
                  (catch Exception e
                    (assoc (merge-config kaocha-defaults {})
                           ::error (str "invalid configuration: " (ex-message e)))))
         plugins (->> (concat (->vec (:kaocha/plugins cfg)) (:plugin cli-options))
                      (map normalize-plugin) (remove nil?) distinct vec)
         [reporter reporter-warnings custom-reporters]
         (volley-reporter (pick (when (seq (:reporter cli-options)) (:reporter cli-options))
                               (:reporter raw)
                               (:kaocha/reporter raw)))
         hooks-on? (contains? (set plugins) hooks/plugin)
         suites (->vec (:kaocha/tests cfg))
         suite-hooks (mapv #(read-hook-config % hooks/suite-keys
                                              (str " (suite " (pr-str (:kaocha.testable/id %)) ")")
                                              hooks-on?)
                           suites)
         top-hooks (read-hook-config cfg hooks/top-level-keys "" hooks-on?)
         tests (mapv (fn [s h] (with-hook-config (suite-view s) h)) suites suite-hooks)
         junit (junit-xml cfg raw cli-options plugins)
         value-errors (volley-value-errors raw)
         runtime (runtime/resolve-runtime (pick (:runtime cli-options)
                                                (when (contains? #{:bb :jvm} (:runtime raw)) (:runtime raw)))
                                          (get env :bb? true))]
     (with-hook-config
      (cond->
       {:tests tests
       :errors (vec (distinct (concat (->vec (:errors raw)) (when-let [e (::error cfg)] [e])
                                      value-errors)))
       :filters (config-filters cfg cli-options)
       :plugins plugins
       :fail-fast? (boolean (pick (:fail-fast cli-options) (:kaocha/fail-fast? cfg) false))
       :color? (boolean (pick (:color cli-options) (:kaocha/color? cfg) true))
       :capture-output? (boolean (pick (:capture-output cli-options)
                                       (:kaocha.plugin.capture-output/capture-output? cfg)
                                       true))
       :reporter reporter
       ;; --print-result prints one result and exits, as without --watch
       :watch? (and (not (:print-result cli-options))
                    (boolean (pick (:watch cli-options) (:kaocha/watch? cfg) false)))
       :workers (pick (:workers cli-options)
                      (when (pos-int? (:workers raw)) (:workers raw))
                      (runtime/default-workers runtime (:cpus env)))
       :isolate? (boolean (pick (:isolate cli-options) (:isolate? raw) false))
       :preload? (boolean (pick (:preload cli-options) (:preload? raw) false))
       :runtime runtime
       :warnings
       (vec (distinct
             (concat
              (when bad? ["tests.edn did not contain a map, using defaults"])
              (->vec (:warnings raw))
              (when (and (= :jvm runtime) (pick (:isolate cli-options) (:isolate? raw)))
                ["--isolate starts a new JVM for every namespace, which is slow"])
              (when-let [ignored (seq (remove supported-plugins plugins))]
                [(str "volley does not run these plugins and ignores their options: "
                      (str/join ", " ignored))])
              (for [k (sort-by str (keys cfg))
                    :when (and (not (silent-key? (plugin-key-namespaces (remove supported-plugins plugins)) k))
                               (not= ::error k))]
                (str "unsupported option " (pr-str k) " ignored"))
              (mapcat suite-warnings suites)
              (hooks-off-warning cfg suites hooks-on?)
              (:warnings top-hooks)
              (mapcat :warnings suite-hooks)
              reporter-warnings
              (when (and (:print-result cli-options) (pick (:watch cli-options) (:kaocha/watch? cfg)))
                ["--watch ignored with --print-result: volley runs once and prints the result"]))))}
       (seq (spec-errors suites)) (assoc :invalid (spec-errors suites))
       junit (assoc :junit-xml junit)
       (seq (remove (some-fn supported-plugins ignored-plugins) plugins))
       (assoc :unknown-plugins (vec (remove (some-fn supported-plugins ignored-plugins) plugins)))
       ;; (:reporters raw): a normalized config normalized again keeps them
       (seq (concat custom-reporters (:reporters raw)))
       (assoc :reporters (vec (distinct (concat custom-reporters (:reporters raw)))))
       (map? (:kaocha/bindings cfg)) (assoc :bindings (:kaocha/bindings cfg))
       true (merge (randomize cfg raw cli-options plugins (:seed env))))
      top-hooks))))

(ns volley.plugins
  "Whether a third-party plugin named in tests.edn would load under Kaocha,
  decided from the source text of the namespaces Kaocha would try. Apart
  from `volley.config` because it parses source with `volley.discover`
  (edamame): `volley.main` loads it only when tests.edn names a plugin
  volley does not know, so other runs never load the parser."
  (:require
   [volley.discover :as discover]
   [clojure.string :as str]))

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

(defn- plugin-id
  "The plugin keyword Kaocha's `defplugin` makes of `id` (`(keyword id)`),
  or nil when `id` is not a name."
  [id]
  (when (or (ident? id) (string? id))
    (keyword id)))

(defn- register-dispatch
  "The dispatch value of `form` when it is a `(defmethod -register v ...)`,
  as Kaocha's `defplugin` expands to, or ::none."
  [form]
  (if (and (head-is? "defmethod" form) (symbol? (second form)) (= "-register" (name (second form))))
    (nth form 2 nil)
    ::none))

(defn- plugin-definition
  "How `forms` define `plugin`: :defined for a `(defplugin id ...)` whose id
  names it, symbol or keyword, or a `-register` method of its keyword;
  :unsure for a `-register` method whose dispatch value is not a keyword,
  such as the `(def id ...)` var Kaocha's own plugins use; else nil."
  [plugin forms]
  (let [found (keep (fn [form]
                      (let [v (register-dispatch form)]
                        (cond
                          (and (head-is? "defplugin" form) (= plugin (plugin-id (second form)))) :defined
                          (= ::none v) nil
                          (= plugin v) :defined
                          (not (keyword? v)) :unsure)))
                    (subforms forms))]
    (or (some #{:defined} found) (first found))))

(defn- wraps-defplugin?
  "True when `forms` define a macro that mentions `defplugin`, which may
  define plugins under another name."
  [forms]
  (boolean (some #(and (head-is? "defmacro" %)
                       (some (fn [f] (and (symbol? f) (= "defplugin" (name f)))) (subforms [%])))
                 (subforms forms))))

(defn- loads-files?
  "True when `forms` load other files, by `(load ...)` or the ns form's
  `(:load ...)`, which the search does not follow."
  [forms]
  (boolean (some #(and (seq? %) (contains? #{'load 'clojure.core/load :load} (first %)))
                 (subforms forms))))

(defn- library-ns?
  "Namespaces that define no third-party plugin, so the search skips them."
  [ns-sym]
  (str/starts-with? (str ns-sym) "clojure."))

(defn- kaocha-ns?
  "Namespaces Kaocha itself provides, which volley's classpath usually
  lacks. A third-party plugin can live under `kaocha.` too, so only a
  missing source is excused."
  [ns-sym]
  (str/starts-with? (str ns-sym) "kaocha."))

(defn- plugin-search
  "Search namespaces `start` and, transitively, what they require for a
  definition of `plugin`. Returns :defined, :unsure when one may hide in
  something unread (a namespace without source other than one of
  Kaocha's, a source that does not parse, a file it `load`s, a macro
  wrapping `defplugin`, a `-register` method whose dispatch value volley
  cannot read), or nil when there is none."
  [plugin source-of start]
  (loop [queue (seq start) seen (set start) unsure? false]
    (if-let [[ns-sym & more] queue]
      (if-let [text (source-of ns-sym)]
        (let [{:keys [forms error]} (discover/read-forms text ns-sym)
              reqs (remove (some-fn seen library-ns?) (:requires (discover/ns-info nil text)))
              found (plugin-definition plugin forms)]
          (if (= :defined found)
            :defined
            (recur (seq (concat more reqs)) (into seen reqs)
                   (or unsure? (= :unsure found) (some? error) (wraps-defplugin? forms) (loads-files? forms)))))
        (recur more seen (or unsure? (not (kaocha-ns? ns-sym)))))
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

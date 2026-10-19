(ns volley.discover
  "Static namespace discovery: file path + file text to namespace info.

  Files are parsed with edamame, never evaluated. Reader conditionals are
  resolved for `:clj`, auto-resolved keywords (`::alias/kw`), regexes,
  syntax-quote and unknown tagged literals are all tolerated. Like
  tools.namespace (which Kaocha uses) the first `ns` form names the file's
  namespace. Metadata is only visible when it is literal in the source."
  (:require
   [edamame.core :as edamame]))

;; ---------------------------------------------------------------------------
;; Reading

(def ^:private read-opts
  {:deref true
   :fn true
   :quote true
   :syntax-quote true
   :var true
   :regex true
   :char true
   :read-eval true
   :read-cond :allow
   :features #{:clj}
   :location? (constantly false)
   :readers (fn [tag] (fn [form] (tagged-literal tag form)))
   :eof ::eof})

(defn read-forms
  "Read every top-level form of `text`, never evaluating it. Returns
  `{:forms [...] :error nil-or-message}`; on a reader error `:forms` holds
  everything read before it. `::kw` reads as `:current/kw`, or with
  namespace `current-ns` when given."
  ([text] (read-forms text 'current))
  ([text current-ns]
   (let [rdr (edamame/reader text)
         opts (assoc read-opts :auto-resolve (fn [alias] (if (= :current alias) current-ns alias)))]
     (loop [forms []]
       (let [step (try {:form (edamame/parse-next rdr opts)}
                       (catch Exception e {:error (or (ex-message e) (str e))}))]
         (cond
           (:error step) {:forms forms :error (:error step)}
           (= ::eof (:form step)) {:forms forms}
           :else (recur (conj forms (:form step)))))))))

;; ---------------------------------------------------------------------------
;; ns form

(defn- head-name [form]
  (when (and (seq? form) (symbol? (first form)))
    (name (first form))))

(defn- ns-form? [form]
  (and (seq? form) (= 'ns (first form)) (symbol? (second form))))

(defn- join-lib [prefix lib]
  (if prefix (symbol (str prefix "." lib)) lib))

(def ^:private loading-opts #{:as :refer :only :rename :exclude :refer-macros})

(defn- vector-spec
  "[lib & opts] -> a lib record, or nil when it isn't one."
  [prefix [lib & opts]]
  (when (symbol? lib)
    (let [ks (set (take-nth 2 opts))]
      {:lib (join-lib prefix lib)
       :loads? (or (not (contains? ks :as-alias))
                   (boolean (some ks loading-opts)))})))

(defn- libspecs
  "Expand one `:require`/`:use` argument into `[{:lib sym :loads? bool}]`,
  handling bare symbols, vectors and prefix lists."
  ([spec] (libspecs nil spec))
  ([prefix spec]
   (cond
     (symbol? spec) [{:lib (join-lib prefix spec) :loads? true}]
     (vector? spec) (some-> (vector-spec prefix spec) vector)
     (and (seq? spec) (symbol? (first spec)))
     (mapcat #(libspecs (join-lib prefix (first spec)) %) (rest spec))
     :else nil)))

(defn- clause-libs [form]
  (when (and (seq? form) (#{:require :use} (first form)))
    (mapcat libspecs (rest form))))

(defn- unquote-form [form]
  (if (and (seq? form) (= 'quote (first form))) (second form) form))

(defn- toplevel-require-libs
  "Libs from a top-level `(require '[...])` or `(use ...)` call."
  [form]
  (when (#{"require" "use"} (head-name form))
    (mapcat (comp libspecs unquote-form) (rest form))))

(defn- parse-ns [form]
  (let [[_ nm & more] form
        [doc more] (if (string? (first more)) [(first more) (rest more)] [nil more])
        [attrs more] (if (map? (first more)) [(first more) (rest more)] [nil more])]
    {:ns nm
     :meta (cond-> (merge (meta nm) attrs)
             doc (assoc :doc doc))
     :libs (mapcat clause-libs more)}))

;; ---------------------------------------------------------------------------
;; Tests

(def ^:private test-defining-heads #{"deftest" "deftest-" "defspec"})

(defn- deftest-var
  "`[sym meta]` for a `deftest`-like form, else nil."
  [form]
  (when (and (contains? test-defining-heads (head-name form))
             (symbol? (second form)))
    [(with-meta (second form) nil) (or (meta (second form)) {})]))

(defn- flatten-do [forms]
  (mapcat (fn [f] (if (= "do" (head-name f)) (flatten-do (rest f)) [f])) forms))

;; ---------------------------------------------------------------------------
;; Public API

(defn ns-info
  "Parse `text`, the contents of the file at `path`, into namespace info:

      {:file \"test/my/app_test.clj\"
       :ns 'my.app-test
       :requires #{'my.app 'clojure.test}   ; libs that get loaded
       :as-alias-only #{'my.specs}          ; `:as-alias` without loading
       :meta {:doc \"...\" :slow true}        ; ns name metadata + attr-map
       :vars {'foo {:slow true}}}           ; literal metadata on deftests

  `:vars` lists vars defined by top-level `deftest`, `deftest-` and `defspec`
  forms (also inside top-level `do`); tests produced by other macros are
  invisible. Top-level `(require ...)`/`(use ...)` calls count towards
  `:requires`. A file without an `ns` form has no `:ns`. A file whose `ns`
  form cannot be read yields `{:file path :error msg}`; a read error after
  the `ns` form keeps the info and adds `:scan-error`. Never throws."
  [path text]
  (let [{:keys [forms error]} (read-forms (or text ""))
        ns-form (first (filter ns-form? forms))]
    (cond
      (and (nil? ns-form) error) {:file path :error error}
      (nil? ns-form) {:file path}
      :else
      (let [{:keys [ns meta libs]} (parse-ns ns-form)
            top (flatten-do (rest (drop-while #(not (identical? ns-form %)) forms)))
            libs (concat libs (mapcat toplevel-require-libs top))
            loaded (into #{} (comp (filter :loads?) (map :lib)) libs)]
        (cond-> {:file path
                 :ns ns
                 :requires loaded
                 :as-alias-only (into #{} (comp (remove :loads?) (map :lib) (remove loaded)) libs)
                 :meta (into {} meta)
                 :vars (into {} (keep deftest-var) top)}
          error (assoc :scan-error error))))))

(ns volley.discover
  "Static namespace discovery: file path + file text to namespace info.

  Files are parsed with edamame, never evaluated. Reader conditionals are
  resolved for `:clj`, auto-resolved keywords (`::alias/kw`), regexes,
  syntax-quote and unknown tagged literals are all tolerated. Like
  tools.namespace (which Kaocha uses) the first `ns` form names the file's
  namespace. Metadata is only visible when it is literal in the source."
  (:require
   [clojure.string :as str]
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
   :auto-resolve (fn [alias] (if (= :current alias) 'current alias))
   :location? (constantly false)
   :readers (fn [tag] (fn [form] (tagged-literal tag form)))
   :eof ::eof})

(defn- read-forms
  "Read every top-level form. Returns `{:forms [...] :error nil-or-message}`;
  on a reader error `:forms` holds everything read before it."
  [text]
  (let [rdr (edamame/reader text)]
    (loop [forms []]
      (let [step (try {:form (edamame/parse-next rdr read-opts)}
                      (catch Exception e {:error (or (ex-message e) (str e))}))]
        (cond
          (:error step) {:forms forms :error (:error step)}
          (= ::eof (:form step)) {:forms forms}
          :else (recur (conj forms (:form step))))))))

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

(defn- normalize-path [p]
  (-> (str p)
      (str/replace #"\\" "/")
      (str/replace #"/+" "/")
      (str/replace #"^(\./)+" "")
      (str/replace #"/$" "")))

(defn under-path?
  "True when `file` is inside directory `dir` (both relative paths)."
  [dir file]
  (let [d (normalize-path dir)
        f (normalize-path file)]
    (or (contains? #{"" "."} d)
        (str/starts-with? f (str d "/")))))

(defn test-file?
  "True when `path` is a Clojure source file (.clj or .cljc) under one of the
  suite's test paths. `suite` is a normalized config suite."
  [suite path]
  (boolean
   (and (re-find #"\.cljc?$" (str path))
        (some #(under-path? % path) (:test-paths suite)))))

(defn ns-match?
  "Mirrors `kaocha.load/ns-match?`: any of the suite's `:ns-patterns` regexes
  is found (`re-find`) in the namespace name."
  [suite ns-sym]
  (boolean
   (some (fn [pattern]
           (try (re-find (re-pattern pattern) (name ns-sym))
                (catch Exception _ nil)))
         (:ns-patterns suite))))

(defn suite-member?
  "True when `info` belongs to the suite's test namespaces: its file is under
  a test path and its namespace matches an ns-pattern. Like Kaocha, a file
  whose ns form cannot be read counts as a member, so the failure is
  reported rather than hidden. Files without an ns form never do."
  [suite {:keys [file ns error]}]
  (boolean
   (and (test-file? suite file)
        (or error (and ns (ns-match? suite ns))))))

(defn mark-tests
  "Set `:test?` on each info: true when it is a member of any suite in
  `suites` (see `suite-member?`)."
  [suites infos]
  (mapv (fn [info] (assoc info :test? (boolean (some #(suite-member? % info) suites))))
        infos))

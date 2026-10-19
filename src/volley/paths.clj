(ns volley.paths
  "Which files and namespaces belong to a suite: test paths and
  `:ns-patterns`, as Kaocha's `kaocha.load` decides. Kept apart from
  `volley.discover`, which parses files, so that planning from namespace
  infos already at hand does not load the parser."
  (:require
   [clojure.string :as str]))

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

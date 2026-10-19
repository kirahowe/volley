(ns volley.deps-graph
  "Namespace dependency graph for watch mode: which test namespaces are
  affected by a set of changed files."
  (:require
   [clojure.set :as set]))

(defn graph
  "Build the dependency graph from `ns-infos` (see `volley.discover/ns-info`,
  with `:test?` set by `volley.paths/mark-tests`). Infos without a
  namespace are ignored.

  Returns
      {:files    {path ns}       ; file -> its namespace
       :deps     {ns #{ns}}      ; ns -> known namespaces it requires
       :rdeps    {ns #{ns}}      ; ns -> known namespaces requiring it
       :tests    #{ns}}          ; test namespaces

  Only namespaces that appear in `ns-infos` become nodes; requires of
  libraries are not edges. Cycles are fine."
  [ns-infos]
  (let [infos (filter :ns ns-infos)
        known (into #{} (map :ns) infos)
        deps (reduce (fn [m {:keys [ns requires]}]
                       (update m ns (fnil into #{}) (filter known) requires))
                     (zipmap known (repeat #{}))
                     infos)
        rdeps (reduce-kv (fn [m ns ds]
                           (reduce #(update %1 %2 (fnil conj #{}) ns) m ds))
                         (zipmap known (repeat #{}))
                         deps)]
    {:files (into {} (map (juxt :file :ns)) infos)
     :deps deps
     :rdeps rdeps
     :tests (into #{} (comp (filter :test?) (map :ns)) infos)}))

(defn- reverse-closure
  "Every namespace reachable from `start` along `rdeps`, including `start`."
  [rdeps start]
  (loop [seen #{} frontier (set start)]
    (if (empty? frontier)
      seen
      (let [seen' (into seen frontier)
            next-frontier (set/difference (into #{} (mapcat #(get rdeps % #{})) frontier) seen')]
        (recur seen' next-frontier)))))

(defn affected
  "The test namespaces affected by changes to `changed-paths`: each changed
  file's namespace plus everything that transitively requires it, restricted
  to test namespaces. A changed test namespace therefore affects itself.

  A changed path the graph does not know (a resource, a config file, a new
  or deleted file) cannot be traced, so the answer is conservative: every
  test namespace is affected."
  [{:keys [files rdeps tests]} changed-paths]
  (let [paths (set changed-paths)
        unknown? (some (complement files) paths)
        changed (into #{} (keep files) paths)]
    (if unknown?
      tests
      (set/intersection tests (reverse-closure rdeps changed)))))

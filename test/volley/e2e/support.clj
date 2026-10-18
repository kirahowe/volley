(ns volley.e2e.support
  "Helpers for end-to-end tests: copy a fixture project from
  `dev-resources/projects/` to a fresh temporary directory and run a
  command there as a real subprocess."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [volley.shell.runtime :as runtime]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def projects-dir
  "Where the fixture projects live."
  "dev-resources/projects")

(def volley-src
  "volley's own source directory, absolute."
  (str (fs/absolutize "src")))

(defn copy-project
  "Copy fixture `project` to a new temporary directory and return its path.
  `files` maps relative paths to contents to add or replace."
  ([project] (copy-project project {}))
  ([project files]
   (let [dir (fs/create-temp-dir {:prefix (str "volley-e2e-" project "-")})]
     (when project
       (fs/copy-tree (fs/path projects-dir project) dir))
     (doseq [[path text] files]
       (fs/create-dirs (fs/parent (fs/path dir path)))
       (spit (str (fs/path dir path)) text))
     (str dir))))

(defn sh
  "Run `cmd` in `dir`. Returns `{:exit :out :err}`."
  [dir cmd & [{:keys [env timeout-ms] :or {timeout-ms 120000}}]]
  (let [proc (p/process cmd {:dir dir :out :string :err :string
                             :extra-env (merge {"NO_COLOR" ""} env)})
        res (deref proc timeout-ms ::timeout)]
    (if (= ::timeout res)
      (do (p/destroy-tree proc)
          {:exit ::timeout :out "" :err "timed out"})
      (select-keys res [:exit :out :err]))))

(defn classpath
  "`entries` joined with the platform path separator."
  [& entries]
  (str/join java.io.File/pathSeparator (remove str/blank? (flatten entries))))

(defn volley
  "Run `bb -m volley.main args` in `dir` with volley's source and the
  project's `src` on the classpath."
  [dir args & [opts]]
  (sh dir (into ["bb" "-cp" (classpath volley-src "src") "-m" "volley.main"] args) opts))

(defn parent-classpath
  "This process's classpath with every entry made absolute, so a child
  started in another directory sees the same libraries."
  []
  (->> (str/split (runtime/parent-classpath) (re-pattern java.io.File/pathSeparator))
       (remove str/blank?)
       (map #(str (fs/absolutize %)))))

(defn last-edn-line
  "The last line of `text` that starts with `prefix`, read as EDN. With
  `strip? true` the prefix itself is not part of the EDN."
  ([text prefix] (last-edn-line text prefix false))
  ([text prefix strip?]
   (some->> (str/split-lines text)
            (filter #(str/starts-with? % prefix))
            last
            (#(if strip? (subs % (count prefix)) %))
            edn/read-string)))

(defn summary-line
  "The `N tests | ...` line of volley's CI output."
  [out]
  (first (filter #(re-find #"^\d+ tests? \|" %) (str/split-lines out))))

(defn print-result
  "volley's own result (`volley.run-state/result`) from the output of a run
  with `--print-result`, which prints Kaocha's result shape with it under
  `:volley/result`."
  [out]
  (:volley/result (last-edn-line out "{:kaocha")))

(defn run
  "Run volley with `args` in a fresh copy of fixture `project`, with
  `files` added (see `copy-project`). Returns `{:exit :out :err}`."
  ([project args] (run project args {}))
  ([project args files] (volley (copy-project project files) args)))

(defn result
  "`--print-result` of a run: `[exit result-map]`."
  [project args]
  (let [{:keys [exit out]} (run project (conj (vec args) "--print-result"))]
    [exit (print-result out)]))

(defn executed
  "Vars that ran, with their status."
  [res]
  (into {} (keep (fn [[v {:keys [status]}]] (when-not (= :skipped status) [v status]))) (:vars res)))

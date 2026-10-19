(ns volley.shell.runtime
  "Worker launch: finds out what the parent runs on, builds the command
  line for bb or JVM workers, and computes (and caches) the classpath for
  `--runtime jvm` from a babashka parent. Shell only; the decisions are in
  `volley.runtime`.

  Portable: it loads on babashka and on the JVM."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [volley.runtime :as runtime]
   [volley.shell.fs :as vfs]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def ^:private sep java.io.File/pathSeparator)

(defn bb?
  "True when this process is babashka."
  []
  (some? (System/getProperty "babashka.version")))

(defn parent-classpath
  "The classpath of this process, as a string."
  []
  (or (when (bb?)
        (when-let [get-classpath (try (requiring-resolve 'babashka.classpath/get-classpath)
                                      (catch Exception _ nil))]
          (get-classpath)))
      (System/getProperty "java.class.path")
      ""))

(defn- absolute-entries [cp]
  (mapv #(str (fs/absolutize %)) (runtime/split-classpath cp sep)))

(defn with-test-paths
  "Classpath `cp` plus every suite's test paths, which Kaocha adds when it
  loads a suite (`kaocha.testable/load`). Source paths are not added: Kaocha
  only uses them for watching."
  [cp cfg]
  (runtime/join-classpath
   (concat (absolute-entries cp)
           (map #(str (fs/absolutize %)) (mapcat :test-paths (:tests cfg))))
   sep))

(defn volley-root
  "The classpath entry volley itself is loaded from (a directory or a jar),
  or nil."
  []
  (when-let [url (some-> (Thread/currentThread) .getContextClassLoader
                         (.getResource "volley/shell/worker.clj"))]
    (let [s (str url)]
      (cond
        (str/starts-with? s "file:")
        (str/replace (.getPath (.toURI ^java.net.URL url)) #"/volley/shell/worker\.clj$" "")
        (str/starts-with? s "jar:file:")
        (-> s (subs (count "jar:file:")) (str/replace #"!/.*$" ""))))))

;; ---------------------------------------------------------------------------
;; JVM parent: forward its JVM options and classpath

(defn invocation
  "The command the user ran volley with (`volley.runtime/invocation`)."
  []
  (let [basis (when-not (bb?)
                (try (some-> (System/getProperty "clojure.basis") slurp edn/read-string)
                     (catch Exception _ nil)))]
    (runtime/invocation {:command (System/getenv "VOLLEY_COMMAND")
                         :bb? (bb?)
                         :task (System/getProperty "babashka.task")
                         :aliases (get-in basis [:basis-config :aliases])
                         :main-opts (get-in basis [:argmap :main-opts])})))

(defn input-arguments
  "This JVM's launch options (`-Xmx...`, `-D...`, `--add-opens ...`)."
  []
  ((requiring-resolve 'volley.shell.jvm/input-arguments)))

(defn java-executable
  "The `java` of this JVM, or `java` from the path when running on
  babashka."
  []
  (let [home (System/getProperty "java.home")
        exe (when (and home (not (bb?))) (fs/path home "bin" "java"))]
    (if (and exe (fs/exists? exe)) (str exe) "java")))

;; ---------------------------------------------------------------------------
;; Classpath for --runtime jvm from a babashka parent

(defn- read-cached
  "The cached classpath in `file` if every jar on it still exists. Source
  directories may legitimately be missing (a `src` not created yet), so
  they do not count."
  [file]
  (when (fs/regular-file? file)
    (let [cp (str/trim (slurp (str file)))
          entries (runtime/split-classpath cp sep)]
      (when (and (seq entries) (every? fs/exists? (filter #(str/ends-with? % ".jar") entries))) cp))))

(defn- compute-classpath
  "Run `clojure -Spath`. Returns `{:classpath s}` or `{:error msg}`."
  [aliases]
  (try
    (let [cmd (runtime/spath-command aliases)
          {:keys [exit out err]} (-> (p/process cmd {:out :string :err :string}) deref)]
      (if (zero? exit)
        {:classpath (str/trim out)}
        {:error (str (str/join " " cmd) " failed: " (str/trim err))}))
    (catch Exception e
      {:error (str "could not run clojure -Spath: " (ex-message e))})))

(defn- read-text [path]
  (try (when (fs/regular-file? path) (slurp (str path)))
       (catch Exception _ nil)))

(defn- other-deps-files
  "The deps.edn files besides the project's that `clojure -Spath` reads:
  those of `:local/root` dependencies, transitively, and the user's."
  [deps-text]
  (let [user (runtime/user-deps-file {:clj-config (System/getenv "CLJ_CONFIG")
                                      :xdg-config-home (System/getenv "XDG_CONFIG_HOME")
                                      :home (System/getProperty "user.home")})]
    (cond-> (runtime/local-deps-files deps-text (str (fs/cwd))
                                      {:read read-text
                                       :join #(str (fs/normalize (fs/path %1 %2)))})
      user (assoc user (read-text user)))))

(defn project-classpath
  "The classpath of the project in the current directory for `aliases`,
  from `clojure -Spath`, cached in `.volley/classpath-<key>.txt` where the
  key hashes deps.edn, the aliases, the deps.edn files of `:local/root`
  dependencies and the user-level deps.edn. Returns `{:classpath s
  :aliases [..] :jvm-opts [..] :cached? bool}` (`:jvm-opts` are the
  aliases' own) or `{:error msg}`."
  [requested-aliases]
  (let [deps-text (read-text "deps.edn")
        aliases (runtime/choose-aliases requested-aliases deps-text)
        jvm-opts (runtime/alias-jvm-opts deps-text aliases)
        file (runtime/cache-file (runtime/classpath-key deps-text aliases (other-deps-files deps-text)))]
    (if-let [cp (read-cached file)]
      {:classpath cp :aliases aliases :jvm-opts jvm-opts :cached? true}
      (let [{:keys [classpath error]} (compute-classpath aliases)]
        (if error
          {:error error}
          (do (try (vfs/ensure-cache-dir! (fs/parent file))
                   (spit file classpath)
                   (catch Exception _ nil))
              {:classpath classpath :aliases aliases :jvm-opts jvm-opts :cached? false}))))))

;; ---------------------------------------------------------------------------
;; The worker command

(defn worker-command
  "The worker command for `cfg` (a normalized config) and the
  `--aliases` the user gave (keywords, or nil). Returns `{:command argv}`, or
  `{:error msg}` when a JVM classpath cannot be computed."
  [cfg aliases]
  (if (= :jvm (:runtime cfg))
    (let [on-jvm? (not (bb?))
          {:keys [classpath error jvm-opts]} (if on-jvm?
                                      {:classpath (parent-classpath)}
                                      (project-classpath aliases))
          root (volley-root)
          classpath (if (and root (not on-jvm?))
                      (str root sep classpath)
                      classpath)]
      (if error
        {:error error}
        {:command (runtime/jvm-worker-command
                   {:java (java-executable)
                    :jvm-args (if on-jvm?
                                (runtime/forward-jvm-args (input-arguments))
                                jvm-opts)
                    :classpath (with-test-paths classpath cfg)})}))
    {:command ["bb" "-cp" (with-test-paths (parent-classpath) cfg) "-m" "volley.shell.worker"]}))

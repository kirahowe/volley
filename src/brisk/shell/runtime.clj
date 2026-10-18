(ns brisk.shell.runtime
  "Worker launch: finds out what the parent runs on, builds the command
  line for bb or JVM workers, and computes (and caches) the classpath for
  `--runtime jvm` from a babashka parent. Shell only; the decisions are in
  `brisk.runtime`.

  Portable: it loads on babashka and on the JVM."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [brisk.runtime :as runtime]
   [clojure.edn :as edn]
   [clojure.string :as str]))

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

(defn brisk-root
  "The classpath entry brisk itself is loaded from (a directory or a jar),
  or nil."
  []
  (when-let [url (some-> (Thread/currentThread) .getContextClassLoader
                         (.getResource "brisk/shell/worker.clj"))]
    (let [s (str url)]
      (cond
        (str/starts-with? s "file:")
        (str/replace (.getPath (.toURI ^java.net.URL url)) #"/brisk/shell/worker\.clj$" "")
        (str/starts-with? s "jar:file:")
        (-> s (subs (count "jar:file:")) (str/replace #"!/.*$" ""))))))

;; ---------------------------------------------------------------------------
;; JVM parent: forward its JVM options and classpath

(defn invocation
  "The command the user ran brisk with (`brisk.runtime/invocation`)."
  []
  (let [basis (when-not (bb?)
                (try (some-> (System/getProperty "clojure.basis") slurp edn/read-string)
                     (catch Exception _ nil)))]
    (runtime/invocation {:command (System/getenv "BRISK_COMMAND")
                         :bb? (bb?)
                         :task (System/getProperty "babashka.task")
                         :aliases (get-in basis [:basis-config :aliases])
                         :main-opts (get-in basis [:argmap :main-opts])})))

(defn input-arguments
  "This JVM's launch options (`-Xmx...`, `-D...`, `--add-opens ...`)."
  []
  ((requiring-resolve 'brisk.shell.jvm/input-arguments)))

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

(defn project-classpath
  "The classpath of the project in the current directory for `aliases`,
  from `clojure -Spath`, cached in `.brisk/classpath-<key>.txt` where the
  key hashes deps.edn and the aliases. Returns `{:classpath s :aliases [..]
  :jvm-opts [..] :cached? bool}` (`:jvm-opts` are the aliases' own) or
  `{:error msg}`."
  [requested-aliases]
  (let [deps-text (when (fs/exists? "deps.edn") (slurp "deps.edn"))
        aliases (runtime/choose-aliases requested-aliases deps-text)
        jvm-opts (runtime/alias-jvm-opts deps-text aliases)
        file (runtime/cache-file (runtime/classpath-key deps-text aliases))]
    (if-let [cp (read-cached file)]
      {:classpath cp :aliases aliases :jvm-opts jvm-opts :cached? true}
      (let [{:keys [classpath error]} (compute-classpath aliases)]
        (if error
          {:error error}
          (do (try (fs/create-dirs (fs/parent file))
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
          root (brisk-root)
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
    {:command ["bb" "-cp" (with-test-paths (parent-classpath) cfg) "-m" "brisk.shell.worker"]}))

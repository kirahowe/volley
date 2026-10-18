(ns brisk.shell.fs
  "File system and environment edge: globbing source files, reading them,
  resolving `#include`, and remembering namespace timings. Everything here
  hands plain data to the pure core."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def timings-file
  "Where per-namespace durations are kept between runs."
  ".brisk/timings.edn")

(defn- relative [path]
  (let [p (fs/path path)]
    (str (if (fs/absolute? p) (fs/relativize (fs/absolutize ".") p) p))))

(defn clojure-files
  "Relative paths of the .clj and .cljc files under `dirs`, sorted and
  without duplicates. Missing directories are skipped."
  [dirs]
  (->> dirs
       (filter #(fs/directory? %))
       (mapcat #(fs/glob % "**.{clj,cljc}"))
       (filter fs/regular-file?)
       (map (comp #(str/replace % "\\" "/") relative str))
       distinct
       sort
       vec))

(defn read-file
  "File contents as a string, or nil when it cannot be read."
  [path]
  (try (slurp (str path)) (catch Exception _ nil)))

(defn exists? [path] (fs/exists? path))

(defn include-resolver
  "An `#include` resolver for `brisk.config/read-config`: paths are
  relative to the including file's directory (the top-level file is
  `config-file`), as Aero's default resolver does. Returns nil for a
  missing file."
  [config-file]
  (fn [path source]
    (let [base (some-> (or source config-file) fs/path fs/parent)
          f (if (and base (not (fs/absolute? path))) (fs/path base path) (fs/path path))]
      (when (fs/regular-file? f) (read-file f)))))

(defn environment
  "Environment the config reader may consult: env vars, system properties,
  host and user names, CPU count, and whether this is babashka."
  []
  {:env (into {} (System/getenv))
   :props (into {} (map (fn [[k v]] [(str k) (str v)])) (System/getProperties))
   :hostname (try (.getHostName (java.net.InetAddress/getLocalHost)) (catch Exception _ nil))
   :user (System/getProperty "user.name")
   :cpus (.availableProcessors (Runtime/getRuntime))
   :bb? (some? (System/getProperty "babashka.version"))})

(defn read-timings
  "Durations saved by the last run, `{ns-sym millis}`; empty when absent or
  unreadable."
  []
  (try
    (let [m (edn/read-string (slurp timings-file))]
      (if (map? m)
        (into {} (filter (fn [[k v]] (and (symbol? k) (number? v)))) m)
        {}))
    (catch Exception _ {})))

(defn write-timings!
  "Save `timings` for the next run. Failure to write is not an error."
  [timings]
  (try
    (fs/create-dirs (fs/parent timings-file))
    (spit timings-file (pr-str (into (sorted-map) timings)))
    (catch Exception _ nil)))

(defn write-file!
  "Write `text` to `path`, creating its directory. Returns nil, or the
  error message when it could not be written."
  [path text]
  (try
    (some-> (fs/parent path) fs/create-dirs)
    (spit (str path) text :encoding "UTF-8")
    nil
    (catch Exception e (or (ex-message e) (str e)))))

(defn absolute
  "Absolute form of `path`."
  [path]
  (str (fs/absolutize path)))

(defn mtime-snapshot
  "Modification times, `{relative-path millis}`, of the files under `dirs`
  plus the existing `files`, for `brisk.watch/changes`. Directories whose
  name starts with a dot are not entered. `keep?` takes a path and decides
  whether to include a file (`brisk.watch/watched-file?`)."
  [dirs files keep?]
  (let [found (volatile! {})
        record! (fn [path millis]
                  (let [p (relative path)]
                    (when (keep? p) (vswap! found assoc (str/replace p "\\" "/") millis))))]
    (doseq [dir dirs :when (fs/directory? dir)]
      (fs/walk-file-tree
       dir
       {:pre-visit-dir (fn [d _]
                         (if (and (not= (str d) (str dir)) (str/starts-with? (str (fs/file-name d)) "."))
                           :skip-subtree
                           :continue))
        :visit-file (fn [f attrs]
                      (record! f (.toMillis (.lastModifiedTime ^java.nio.file.attribute.BasicFileAttributes attrs)))
                      :continue)}))
    (doseq [f files :when (fs/regular-file? f)]
      (record! f (.toMillis (fs/last-modified-time f))))
    @found))

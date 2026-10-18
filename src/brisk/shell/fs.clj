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
  host and user names, CPU count."
  []
  {:env (into {} (System/getenv))
   :props (into {} (map (fn [[k v]] [(str k) (str v)])) (System/getProperties))
   :hostname (try (.getHostName (java.net.InetAddress/getLocalHost)) (catch Exception _ nil))
   :user (System/getProperty "user.name")
   :cpus (.availableProcessors (Runtime/getRuntime))})

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

(defn absolute
  "Absolute form of `path`."
  [path]
  (str (fs/absolutize path)))

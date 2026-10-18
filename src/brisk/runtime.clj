(ns brisk.runtime
  "Pure decisions for choosing and launching a worker runtime: which
  runtime to use, which JVM options to pass on to JVM workers, how many
  workers to start by default, which deps.edn aliases to put on the
  classpath, and what to key a cached classpath on. The shell
  (`brisk.shell.runtime`) gathers the inputs and does the I/O."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str])
  (:import
   (java.security MessageDigest)))

(defn resolve-runtime
  "The worker runtime: an explicit `requested` (`:bb` or `:jvm`) wins,
  otherwise workers use the runtime the parent runs on."
  [requested bb-parent?]
  (or requested (if bb-parent? :bb :jvm)))

(def ^:private max-workers {:bb 8 :jvm 4})

(defn default-workers
  "Default worker count: the CPU count, capped at 8 on babashka and at 4 on
  the JVM, where every worker is a full JVM with the project loaded."
  [runtime cpus]
  (-> (or cpus 4) (min (get max-workers runtime 8)) (max 1)))

;; ---------------------------------------------------------------------------
;; JVM options

(def ^:private dropped-prefixes
  "Options that do not make sense in a second JVM, because they claim a
  port or a file the parent already holds, or because they are about the
  launcher rather than the program."
  ["-agentlib:jdwp" "-Xrunjdwp" "-Xdebug" "-Xnoagent"
   "-Dcom.sun.management.jmxremote"
   "-Dclojure.server."
   "-Djava.class.path="
   "-Xlog"
   "-XX:StartFlightRecording" "-XX:FlightRecorderOptions"
   "-XX:ErrorFile=" "-XX:HeapDumpPath=" "-XX:LogFile=" "-XX:+LogVMOutput"])

(def ^:private class-path-flags #{"-cp" "-classpath" "--class-path"})

(defn- dropped? [arg]
  (some #(str/starts-with? arg %) dropped-prefixes))

(defn forward-jvm-args
  "The parent JVM's input arguments (`RuntimeMXBean.getInputArguments`)
  that JVM workers should start with. Everything is forwarded (heap size,
  GC and `-XX` flags, `-D` properties, `--add-opens`, agents) except
  debugger agents, JMX remote ports, socket REPL properties, JFR and
  unified logging, files that would collide (error file, heap dump path)
  and class path options, since workers get their own classpath."
  [input-args]
  (->> input-args
       (reduce (fn [{:keys [skip?] :as acc} arg]
                 (cond
                   skip? (assoc acc :skip? false)
                   (contains? class-path-flags arg) (assoc acc :skip? true)
                   (dropped? arg) acc
                   :else (update acc :args conj arg)))
               {:skip? false :args []})
       :args))

;; ---------------------------------------------------------------------------
;; Classpath for `--runtime jvm` from a babashka parent

(defn- alias-keyword [s]
  (let [s (str/replace-first (str s) #"^:" "")]
    (when-not (str/blank? s) (keyword s))))

(defn parse-aliases
  "A `--aliases` value such as `:test:dev` or `test,dev` as a vector of
  keywords."
  [s]
  (->> (str/split (str s) #"[:,]")
       (keep alias-keyword)
       vec))

(defn- read-deps [deps-text]
  (try (let [m (edn/read-string deps-text)] (when (map? m) m))
       (catch Exception _ nil)))

(defn choose-aliases
  "The aliases for `clojure -Spath`: the explicit `requested` ones, else
  `[:test]` when deps.edn (text, or nil when missing) defines a `:test`
  alias, else none."
  [requested deps-text]
  (cond
    (seq requested) (vec requested)
    (contains? (:aliases (read-deps (or deps-text ""))) :test) [:test]
    :else []))

(defn alias-jvm-opts
  "The `:jvm-opts` of `aliases` in deps.edn (text, or nil), in alias order.
  `clojure -Spath` does not report them, and the Clojure CLI would pass
  them to the JVM when running the alias, so JVM workers started from a
  babashka parent get them here."
  [deps-text aliases]
  (let [defined (:aliases (read-deps (or deps-text "")))]
    (into [] (comp (mapcat #(get-in defined [% :jvm-opts])) (filter string?)) aliases)))

(defn spath-command
  "The command that prints the classpath for `aliases`."
  [aliases]
  (cond-> ["clojure" "-Spath"]
    (seq aliases) (conj (str "-A" (apply str (map #(str ":" (name %)) aliases))))))

(defn- hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

(defn classpath-key
  "Cache key for a computed classpath: a SHA-256 of the deps.edn text
  (nil when missing) and the aliases, shortened to 16 hex characters. Equal
  inputs give equal keys; changing the text or the aliases changes it."
  [deps-text aliases]
  (let [md (MessageDigest/getInstance "SHA-256")
        input (pr-str [(or deps-text "") (mapv name aliases)])]
    (subs (hex (.digest md (.getBytes ^String input "UTF-8"))) 0 16)))

(defn cache-file
  "Where the classpath for `key` is cached."
  [key]
  (str ".brisk/classpath-" key ".txt"))

(defn split-classpath
  "Entries of a classpath string."
  [cp sep]
  (->> (str/split (or cp "") (re-pattern (java.util.regex.Pattern/quote sep)))
       (remove str/blank?)
       vec))

(defn join-classpath
  "Entries joined into a classpath, first occurrence of each kept."
  [entries sep]
  (str/join sep (distinct (remove str/blank? entries))))

;; ---------------------------------------------------------------------------
;; Commands

(def default-jvm-args
  "Options every JVM worker starts with, before the forwarded ones, so a
  forwarded option for the same setting wins. Test runs are short, and
  limiting the JIT to the C1 compiler cuts about a fifth off a typical
  suite's wall time (no wait for C2 compilation); a suite that is mostly
  long CPU-bound loops can pass `-XX:TieredStopAtLevel=4` to undo it."
  ["-XX:TieredStopAtLevel=1"])

(defn jvm-worker-command
  "argv for a JVM worker: the `java` executable, `default-jvm-args`, the
  forwarded JVM options, the classpath, and `clojure.main -m
  brisk.shell.worker`."
  [{:keys [java jvm-args classpath]}]
  (-> [(or java "java")]
      (into default-jvm-args)
      (into jvm-args)
      (into ["-cp" classpath "clojure.main" "-m" "brisk.shell.worker"])))

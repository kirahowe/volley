(ns volley.runtime
  "Pure decisions for choosing and launching a worker runtime: which
  runtime to use, which JVM options to pass on to JVM workers, how many
  workers to start by default, which deps.edn aliases to put on the
  classpath, and what to key a cached classpath on. The shell
  (`volley.shell.runtime`) gathers the inputs and does the I/O."
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

(defn invocation
  "How the user ran volley, for the command in the rerun hint: `command`
  (the `VOLLEY_COMMAND` environment variable, which `bin/volley` sets), else
  `bb <task>` inside a babashka task, else `bb -m volley.main` on babashka,
  else the Clojure CLI with the aliases of the JVM's basis (and `-m
  volley.main` unless one of them runs it through `:main-opts`)."
  [{:keys [command bb? task aliases main-opts]}]
  (cond
    (not (str/blank? command)) command
    (and bb? task) (str "bb " task)
    bb? "bb -m volley.main"
    :else (str "clojure -M" (str/join (map str aliases))
               (when-not (some #{"volley.main"} main-opts) " -m volley.main"))))

(def ^:private max-workers {:bb 8 :jvm 4})

(defn default-workers
  "Default worker count: the CPU count, capped at 8 on babashka and at 4 on
  the JVM, where every worker is a full JVM with the project loaded."
  [runtime cpus]
  (-> (or cpus 4) (min (get max-workers runtime 8)) (max 1)))

;; ---------------------------------------------------------------------------
;; In process or in worker processes

(def in-process-limit-ms
  "The longest run that is worth running inside the parent JVM. A JVM
  worker costs about half a second to start and loads the project's code
  again, which a run of a few seconds never wins back; past this, parallel
  workers can. An in-process run that is still going after this long
  starts worker processes for the namespaces left (`volley.engine`)."
  10000)

(def recent-runs
  "How many run times of each mode the history keeps."
  3)

(defn typical
  "The median of `times` (the lower middle one of an even count), or nil
  when there are none."
  [times]
  (when (seq times)
    (nth (sort times) (quot (dec (count times)) 2))))

(defn heuristic-run?
  "True when `worker-plan` decides how run `mode` gets its workers: a JVM
  parent (`:parent`) with JVM workers (`:runtime`), the default worker
  count (no `:workers-set?`), and neither `:watch?` nor `:isolate?`. Its
  wall time then measures the setup the next plan chooses between."
  [{:keys [parent runtime workers-set? watch? isolate?]}]
  (and (= :jvm parent) (= :jvm runtime) (not workers-set?) (not watch?) (not isolate?)))

(defn hook-namespaces
  "Namespaces of the hooks, worker setup fns and reporters a worker
  resolves from the `:configure` message `msg`."
  [msg]
  (->> (select-keys msg [:hooks :worker-setup :reporters :suites])
       (tree-seq coll? seq)
       (filter qualified-symbol?)
       (map (comp symbol namespace))
       distinct))

(defn in-process-ok?
  "Whether `plan` can run on a thread of the parent: `mode` is a
  `heuristic-run?`, and every planned or preloaded namespace, and every
  namespace a hook or reporter of `:configure` names, loads from the
  parent's classpath by `(on-classpath? ns-sym)` (a JVM worker's adds the
  test paths)."
  [mode plan configure on-classpath?]
  (boolean (and (heuristic-run? mode)
                (every? on-classpath?
                        (concat (map :ns (mapcat :namespaces (:suites plan)))
                                (map :ns (:preload plan))
                                (hook-namespaces configure))))))

(defn record-history?
  "Whether a complete run in `mode` goes into the history: a
  `heuristic-run?` of the whole plan, with no suite (`cli-suites`) or
  filter (`cli-options`) given on the command line."
  [mode cli-suites cli-options]
  (boolean (and (heuristic-run? mode)
                (empty? cli-suites)
                (not-any? #(seq (get cli-options %)) [:focus :skip :focus-meta :skip-meta]))))

(def retry-after
  "How many short worker-process runs since the last in-process run make
  the next one try in process again, though the in-process runs on record
  were slow. A busy machine or a run that scaled out can make in-process runs
  look slow, and only another in-process run can show otherwise."
  5)

(defn- short-run? [ms]
  (and (number? ms) (<= ms in-process-limit-ms)))

(defn- long-run? [ms]
  (and (number? ms) (> ms in-process-limit-ms)))

(defn serial-ms
  "How long `namespaces` took one after another by the last run's
  `durations` (`.volley/timings.edn`, `{ns-sym millis}`): the sum of the
  ones on record, or nil when none is. A run in process runs them on one
  thread, so this, not the wall time of a run with parallel workers, is
  what it would take."
  [durations namespaces]
  (let [known (keep #(get durations %) (distinct namespaces))]
    (when (seq known) (reduce + known))))

(defn worker-plan
  "How a run gets its workers: `{:workers n :in-process? bool}`.
  `:in-process?` runs the tests on a thread of the parent JVM, starting
  worker processes, up to `:workers`, only if the run outlasts
  `in-process-limit-ms`. Input:

  * `:workers`         the configured or default worker count
  * `:workers-set?`    true when `--workers` or tests.edn chose it: then
                       always that many worker processes
  * `:in-process-ok?`  whether this plan can run in process
                       (`in-process-ok?`)
  * `:history`         `record-run`'s map from earlier runs
  * `:serial-ms`       the plan's namespaces one after another
                       (`serial-ms`), or nil when unknown

  In process when the recent in-process runs typically took at most
  `in-process-limit-ms`. Otherwise the recent runs with worker processes
  must typically have been that short, and then in process when no
  in-process run is on record, after one slow in-process run when
  `:serial-ms` is not over `in-process-limit-ms` (one slow run on a busy
  machine proves nothing), or after `retry-after` short worker-process
  runs. Typically means the median of the last `recent-runs` (the lower
  middle one of two), so one slow run on a busy machine changes nothing.
  With no history, worker processes: a first run (or CI) behaves as
  before.

  `:serial-ms` cannot stop the first try: after worker-process runs it
  counts each worker's setup again (hosted-clay sums to 28s and runs in
  6s on one thread), so only a run in process can tell. It stops the
  second: a suite that takes 10s on four workers but 30s on one thread
  then waits for `retry-after`."
  [{:keys [workers workers-set? in-process-ok? history serial-ms]}]
  (let [{:keys [in-process-ms workers-ms workers-streak]} history
        tries (count in-process-ms)]
    {:workers workers
     :in-process? (boolean (and in-process-ok?
                                (not workers-set?)
                                (or (short-run? (typical in-process-ms))
                                    (and (short-run? (typical workers-ms))
                                         (or (zero? tries)
                                             (and (= 1 tries) (not (long-run? serial-ms)))
                                             (>= (or workers-streak 0) retry-after))))))}))

(defn record-run
  "`history` after a run that ran every planned namespace, in process
  (`:in-process?`) or not, in `:wall-ms`: the run's mode keeps its last
  `recent-runs` times, newest last; the other mode keeps its own.
  `:workers-streak` counts the short worker-process runs since the last
  in-process one; a slow one leaves it as it was."
  [history {:keys [in-process? wall-ms]}]
  (let [history (if (map? history) history {})
        streak (if (nat-int? (:workers-streak history)) (:workers-streak history) 0)]
    (-> history
        (update (if in-process? :in-process-ms :workers-ms)
                (fn [times] (vec (take-last recent-runs (conj (vec (filter number? times)) (long wall-ms))))))
        (assoc :workers-streak (cond in-process? 0
                                     (short-run? wall-ms) (inc streak)
                                     :else streak)))))

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

(def ^:private dep-map-keys
  "The keys of an alias that hold a dependency map."
  [:extra-deps :override-deps :default-deps :replace-deps :deps])

(defn local-roots
  "The `:local/root` paths in deps.edn (text, or nil), sorted: from `:deps`
  and from every alias, since an alias not chosen today costs only a
  cache miss if it is chosen later."
  [deps-text]
  (let [{:keys [deps aliases]} (read-deps (or deps-text ""))
        dep-maps (cons deps (when (map? aliases)
                              (for [a (vals aliases) :when (map? a) k dep-map-keys] (get a k))))]
    (->> dep-maps
         (filter map?)
         (mapcat vals)
         (keep #(when (map? %) (:local/root %)))
         (filter string?)
         distinct
         sort
         vec)))

(defn local-deps-files
  "Every deps.edn reachable from `deps-text` (the project's, in directory
  `dir`) through `:local/root`, transitively, as `{path text-or-nil}`.
  `read` takes a path and returns its text or nil; `join` resolves a root
  against the directory of the deps.edn that names it. A cycle of local
  roots ends where it repeats."
  [deps-text dir {:keys [read join]}]
  (loop [found {} todo (mapv #(join dir %) (local-roots deps-text))]
    (if-let [root (first todo)]
      (let [file (join root "deps.edn")]
        (if (contains? found file)
          (recur found (subvec todo 1))
          (let [text (read file)]
            (recur (assoc found file text)
                   (into (subvec todo 1) (map #(join root %)) (local-roots text))))))
      found)))

(defn user-deps-file
  "The user-level deps.edn the Clojure CLI reads: `$CLJ_CONFIG/deps.edn`,
  else `$XDG_CONFIG_HOME/clojure/deps.edn`, else `~/.clojure/deps.edn`, as
  in the `clojure` script. Blank values count as unset; nil without a
  home."
  [{:keys [clj-config xdg-config-home home]}]
  (let [given #(when-not (str/blank? %) %)]
    (cond
      (given clj-config) (str clj-config "/deps.edn")
      (given xdg-config-home) (str xdg-config-home "/clojure/deps.edn")
      (given home) (str home "/.clojure/deps.edn"))))

(defn classpath-key
  "Cache key for a computed classpath: a SHA-256 of the deps.edn text
  (nil when missing), the aliases and the other deps.edn files the
  classpath depends on (`{path text-or-nil}`: local roots, the user's),
  shortened to 16 hex characters. Equal inputs give equal keys; changing
  any text, any path or the aliases changes it."
  ([deps-text aliases] (classpath-key deps-text aliases {}))
  ([deps-text aliases others]
   (let [md (MessageDigest/getInstance "SHA-256")
         input (pr-str (cond-> [(or deps-text "") (mapv name aliases)]
                         (seq others) (conj (sort-by key others))))]
     (subs (hex (.digest md (.getBytes ^String input "UTF-8"))) 0 16))))

(defn cache-file
  "Where the classpath for `key` is cached."
  [key]
  (str ".volley/classpath-" key ".txt"))

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
;; Recomputing the parent's classpath (watch mode, after deps.edn or bb.edn
;; changed)

(defn parent-classpath-command
  "The command that prints the classpath the parent would start with now:
  babashka's for the bb.edn in the current directory, or `clojure -Spath`
  with the `aliases` of the JVM parent's basis."
  [{:keys [bb? aliases]}]
  (if bb?
    ["bb" "-e" "(print (babashka.classpath/get-classpath))"]
    (spath-command aliases)))

(defn refreshed-classpath
  "The `fresh` classpath followed by the entries of the `startup` one it
  lacks. Fresh entries come first so a changed dependency version wins;
  the startup entries stay because they hold what the parent was started
  with on top of the project file (a bb task's `:extra-paths` and
  `:extra-deps`, volley itself)."
  [fresh startup sep]
  (join-classpath (concat (split-classpath fresh sep) (split-classpath startup sep)) sep))

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
  volley.shell.worker`."
  [{:keys [java jvm-args classpath]}]
  (-> [(or java "java")]
      (into default-jvm-args)
      (into jvm-args)
      (into ["-cp" classpath "clojure.main" "-m" "volley.shell.worker"])))

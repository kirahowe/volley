(ns brisk.cli
  "Command-line parsing that matches Kaocha's runner.

  The flag table mirrors `kaocha.runner/cli-options` plus the options added by
  Kaocha's default plugins (filter, randomize, capture-output), the harmless
  profiling/notifier flags, and brisk's own `--workers`, `--isolate` and
  `--runtime`. Tokenising follows `clojure.tools.cli/parse-opts` as Kaocha
  calls it: options and positional arguments may be interleaved, `--` ends
  option parsing, `--opt=value` works for options that take a value, and
  `--[no-]flag` toggles. Parsing never throws; problems become `:errors`."
  (:require
   [clojure.string :as str]))

(defn- parse-kw
  "Mirrors `kaocha.runner/parse-kw`: a leading colon is optional."
  [value]
  (keyword (if (= \: (first value)) (subs value 1) value)))

(defn- parse-reporter
  "Mirrors the `--reporter` parse-fn: bare names live in `kaocha.report`."
  [s]
  (let [sym (symbol s)]
    (if (qualified-symbol? sym)
      sym
      (symbol "kaocha.report" s))))

(defn- parse-plugin
  "Mirrors the `--plugin` parse-fn: bare names live in `kaocha.plugin`."
  [s]
  (let [kw (parse-kw s)]
    (if (qualified-keyword? kw)
      kw
      (keyword "kaocha.plugin" s))))

(defn- parse-int [s]
  (or (parse-long s)
      (throw (ex-info "not an integer" {:value s}))))

(defn- parse-pos-int [s]
  (let [n (parse-int s)]
    (if (pos? n)
      n
      (throw (ex-info "must be a positive integer" {:value s})))))

(defn- parse-runtime [s]
  (case (str/replace-first s #"^:" "")
    "bb" :bb
    "jvm" :jvm
    (throw (ex-info "must be bb or jvm" {:value s}))))

(def ^:private option-specs
  ;; :arg is the value placeholder (absent for flags); :toggle? means the
  ;; option also answers to --no-<long>; :multi? accumulates into a vector.
  [{:short "-c" :long "config-file" :arg "FILE" :default "tests.edn"
    :doc "Config file to read."}
   {:long "print-config" :doc "Print out the fully merged and normalized config, then exit."}
   {:long "print-test-plan" :doc "Load tests, build up a test plan, then print out the test plan and exit."}
   {:long "print-result" :doc "Print the test result map as returned by the Kaocha API."}
   {:long "fail-fast" :toggle? true :doc "Stop testing after the first failure."}
   {:long "color" :toggle? true :doc "Enable/disable ANSI color codes in output. Defaults to true."}
   {:long "watch" :toggle? true :doc "Watch filesystem for changes and re-run tests."}
   {:long "reporter" :arg "SYMBOL" :multi? true :parse parse-reporter
    :doc "Change the test reporter, can be specified multiple times."}
   {:long "diff-style" :arg "STYLE" :parse parse-kw
    :doc "The style of diff to print on failing tests, either :none or :deep"}
   {:long "plugin" :arg "KEYWORD" :multi? true :parse parse-plugin
    :doc "Load the given plugin."}
   {:long "profile" :arg "KEYWORD" :parse parse-kw
    :doc "Configuration profile. Defaults to :default or :ci."}
   {:long "version" :doc "Print version information and quit."}
   {:long "help" :doc "Display this help message."}
   {:short "-H" :long "test-help" :doc "Display this help message."}
   ;; kaocha.plugin/filter
   {:long "skip" :arg "SYM" :multi? true :parse parse-kw
    :doc "Skip tests with this ID and their children."}
   {:long "focus" :arg "SYM" :multi? true :parse parse-kw
    :doc "Only run this test, skip others."}
   {:long "skip-meta" :arg "SYM" :multi? true :parse parse-kw
    :doc "Skip tests where this metadata key is truthy."}
   {:long "focus-meta" :arg "SYM" :multi? true :parse parse-kw
    :doc "Only run tests where this metadata key is truthy."}
   ;; kaocha.plugin/randomize
   {:long "randomize" :toggle? true :doc "Run test namespaces and vars in random order."}
   {:long "seed" :arg "SEED" :parse parse-int
    :doc "Provide a seed to determine the random order of tests."}
   ;; kaocha.plugin/capture-output
   {:long "capture-output" :toggle? true :doc "Capture output during tests."}
   ;; kaocha.plugin/profiling and friends: accepted so existing invocations
   ;; keep working; brisk always reports slow tests.
   {:long "profiling" :toggle? true
    :doc "Show slowest tests of each type with timing information."}
   {:long "profiling-count" :arg "NUM" :parse parse-int
    :doc "Show this many slow tests of each kind in profile results."}
   {:long "notifications" :toggle? true
    :doc "Enable/disable the notifier plugin (ignored by brisk)."}
   {:long "notification-timeout" :arg "TIMEOUT"
    :doc "Timeout for desktop notifications (ignored by brisk)."}
   {:long "gc-profiling" :toggle? true :doc "Approximate memory per test (ignored by brisk)."}
   {:long "gc-profiling-individual" :toggle? true
    :doc "Details of individual tests for gc-profiling (ignored by brisk)."}
   ;; brisk's own
   {:long "workers" :arg "N" :parse parse-pos-int
    :doc "Number of parallel worker processes."}
   {:long "isolate" :toggle? true
    :doc "Run every namespace in a fresh worker process."}
   {:long "runtime" :arg "RUNTIME" :parse parse-runtime
    :doc "Worker runtime: bb or jvm."}])

(defn- spec-key [{:keys [long]}] (keyword long))

(def ^:private by-long
  (into {}
        (mapcat (fn [{:keys [long toggle?] :as spec}]
                  (cond-> [[long [spec true]]]
                    toggle? (conj [(str "no-" long) [spec false]]))))
        option-specs))

(def ^:private by-short
  (into {} (keep (fn [spec] (some-> (:short spec) (vector spec)))) option-specs))

(defn- display-name
  "The text tools.cli shows for an option in error messages."
  [{:keys [short long arg]}]
  (str/join " " (remove nil? [(or short (str "--" long)) arg])))

(defn- apply-value
  "Parse `raw` for `spec` and store it. Returns the updated state."
  [state spec raw invoked]
  (let [{:keys [parse multi?]} spec
        k (spec-key spec)]
    (try
      (let [v ((or parse identity) raw)]
        (update state :options
                (fn [opts] (if multi? (update opts k (fnil conj []) v) (assoc opts k v)))))
      (catch Exception e
        (update state :errors conj
                (str "Error while parsing option \"" invoked " " raw "\": "
                     (or (ex-message e) (str e))))))))

(defn- unknown [state shown]
  (update state :errors conj (str "Unknown option: \"" shown "\"")))

(defn- missing [state spec]
  (update state :errors conj
          (str "Missing required argument for \"" (display-name spec) "\"")))

(defn- set-flag [state spec on?]
  (assoc-in state [:options (spec-key spec)] on?))

(defn- take-value
  "Option `spec` needs a value: use `inline` if present, else the next token."
  [state spec inline invoked [token & more :as tokens]]
  (cond
    (some? inline) [(apply-value state spec inline invoked) tokens]
    (empty? tokens) [(missing state spec) tokens]
    :else [(apply-value state spec token invoked) more]))

(defn- long-option
  "Handle `--name` or `--name=value`. Returns [state remaining-tokens]."
  [state token tokens]
  (let [[_ name inline] (re-matches #"(?s)--([^=]+)(?:=(.*))?" token)
        [spec on?] (get by-long name)]
    (cond
      (nil? spec) [(unknown state (str "--" name)) tokens]
      (:arg spec) (take-value state spec inline (str "--" name) tokens)
      :else [(set-flag state spec on?) tokens])))

(defn- short-option
  "Handle `-x`, `-xVALUE` and bundled flags like `-Hc FILE`."
  [state chars tokens]
  (if (empty? chars)
    [state tokens]
    (let [flag (str "-" (first chars))
          spec (get by-short flag)
          more (subs chars 1)]
      (cond
        (nil? spec) (short-option (unknown state flag) more tokens)
        (:arg spec) (take-value state spec (not-empty more) flag tokens)
        :else (short-option (set-flag state spec true) more tokens)))))

(defn- defaults []
  (into {}
        (keep (fn [spec] (when (contains? spec :default) [(spec-key spec) (:default spec)])))
        option-specs))

(defn parse-args
  "Parse `argv` (a seq of strings) the way Kaocha's runner does.

  Returns `{:options {...} :suites [kw ...] :errors [str ...]}`. Option keys
  follow tools.cli: `--no-color` gives `{:color false}`, repeatable options
  (`--focus`, `--skip`, `--focus-meta`, `--skip-meta`, `--reporter`,
  `--plugin`) collect into vectors, and `:config-file` defaults to
  \"tests.edn\". Positional arguments are suite ids, with an optional leading
  colon. Never throws."
  [argv]
  (loop [state {:options (defaults) :arguments [] :errors []}
         [token & more :as tokens] (map str argv)]
    (cond
      (empty? tokens)
      {:options (:options state)
       :suites (into [] (comp (map parse-kw) (distinct)) (:arguments state))
       :errors (:errors state)}

      (= "--" token)
      (recur (update state :arguments into more) nil)

      (str/starts-with? token "--")
      (let [[state' rest'] (long-option state token more)]
        (recur state' rest'))

      (and (str/starts-with? token "-") (> (count token) 1))
      (let [[state' rest'] (short-option state (subs token 1) more)]
        (recur state' rest'))

      :else
      (recur (update state :arguments conj token) more))))

(defn- format-row [{:keys [short long arg toggle? default doc]}]
  [(str (if short (str short ", ") "    ")
        "--" (if toggle? (str "[no-]" long) long)
        (when arg (str " " arg)))
   (some-> default str)
   doc])

(defn help-text
  "Usage text for `--help`, in the shape of Kaocha's own."
  []
  (let [rows (map format-row option-specs)
        w1 (apply max (map (comp count first) rows))
        w2 (apply max (map (comp count #(or % "") second) rows))
        pad (fn [s w] (str s (apply str (repeat (- w (count (or s ""))) \space))))]
    (str "USAGE:\n\n"
         "bb brisk [OPTIONS]... [TEST-SUITE]...\n\n"
         (->> rows
              (map (fn [[flag default doc]]
                     (str "  " (pad flag w1) "  " (pad default w2) "  " doc)))
              (str/join "\n"))
         "\n\nOptions may be repeated multiple times for a logical OR effect.")))

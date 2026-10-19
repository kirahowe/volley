(ns volley.cli
  "Command-line parsing that matches Kaocha's runner.

  The flag table mirrors `kaocha.runner/cli-options` plus the options added by
  Kaocha's default plugins (filter, randomize, capture-output), the harmless
  profiling/notifier flags, and volley's own `--workers`, `--isolate` and
  `--runtime` and `--aliases`. Tokenising follows `clojure.tools.cli/parse-opts` as Kaocha
  calls it: options and positional arguments may be interleaved, `--` ends
  option parsing, `--opt=value` works for options that take a value, and
  `--[no-]flag` toggles. Parsing never throws; problems become `:errors`."
  (:require
   [volley.runtime :as runtime]
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
   {:long "color" :toggle? true
    :doc "Enable/disable ANSI color codes in output. Defaults to on when stdout is a terminal; NO_COLOR turns it off."}
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
   {:long "randomize" :toggle? true
    :doc "Run the vars of each namespace in random order."}
   {:long "seed" :arg "SEED" :parse parse-int
    :doc "Provide a seed to determine the random order of tests."}
   ;; kaocha.plugin/capture-output
   {:long "capture-output" :toggle? true :doc "Capture output during tests."}
   ;; Flags of other plugins, valid only when the plugin is listed
   ;; (`plugin-flag-errors`), as Kaocha only knows them then.
   ;; kaocha.plugin/profiling: volley always lists the slowest tests.
   {:long "profiling" :toggle? true :plugin :kaocha.plugin/profiling
    :doc "Show slowest tests of each type with timing information (kaocha.plugin/profiling; ignored)."}
   {:long "profiling-count" :arg "NUM" :parse parse-int :plugin :kaocha.plugin/profiling
    :doc "Show this many slow tests of each kind in profile results (ignored)."}
   ;; kaocha.plugin/notifier and gc-profiling, which volley does not run
   {:long "notifications" :toggle? true :plugin :kaocha.plugin/notifier
    :doc "Enable/disable the notifier plugin (ignored by volley)."}
   {:long "notification-timeout" :arg "TIMEOUT" :plugin :kaocha.plugin/notifier
    :doc "Timeout for desktop notifications (ignored by volley)."}
   {:long "gc-profiling" :toggle? true :plugin :kaocha.plugin/gc-profiling
    :doc "Approximate memory per test (ignored by volley)."}
   {:long "gc-profiling-individual" :toggle? true :plugin :kaocha.plugin/gc-profiling
    :doc "Details of individual tests for gc-profiling (ignored by volley)."}
   ;; kaocha.plugin/junit-xml, which volley implements:
   {:long "junit-xml-file" :arg "FILENAME" :plugin :kaocha.plugin/junit-xml
    :doc "Save the test results to an Ant JUnit XML file (kaocha.plugin/junit-xml)."}
   {:long "junit-xml-omit-system-out" :plugin :kaocha.plugin/junit-xml
    :doc "Do not add captured output to junit.xml (kaocha.plugin/junit-xml)."}
   {:long "junit-xml-add-location-metadata" :plugin :kaocha.plugin/junit-xml
    :doc "Add line and file attributes to tests in junit.xml (kaocha.plugin/junit-xml)."}
   ;; kaocha.plugin/cloverage, which volley does not run: accepted and ignored.
   {:long "cov-output" :arg "PATH" :plugin :kaocha.plugin/cloverage :doc "Ignored by volley (cloverage)."}
   {:long "cov-text" :toggle? true :plugin :kaocha.plugin/cloverage :doc "Ignored by volley (cloverage)."}
   {:long "cov-html" :toggle? true :plugin :kaocha.plugin/cloverage :doc "Ignored by volley (cloverage)."}
   {:long "emma-xml" :toggle? true :plugin :kaocha.plugin/cloverage :doc "Ignored by volley (cloverage)."}
   {:long "lcov" :toggle? true :plugin :kaocha.plugin/cloverage :doc "Ignored by volley (cloverage)."}
   {:long "codecov" :toggle? true :plugin :kaocha.plugin/cloverage :doc "Ignored by volley (cloverage)."}
   {:long "coveralls" :toggle? true :plugin :kaocha.plugin/cloverage :doc "Ignored by volley (cloverage)."}
   {:long "cov-summary" :toggle? true :plugin :kaocha.plugin/cloverage :doc "Ignored by volley (cloverage)."}
   {:long "cov-fail-threshold" :arg "PERCENT" :parse parse-int :plugin :kaocha.plugin/cloverage
    :doc "Ignored by volley (cloverage)."}
   {:long "cov-low-watermark" :arg "PERCENT" :parse parse-int :plugin :kaocha.plugin/cloverage
    :doc "Ignored by volley (cloverage)."}
   {:long "cov-high-watermark" :arg "PERCENT" :parse parse-int :plugin :kaocha.plugin/cloverage
    :doc "Ignored by volley (cloverage)."}
   {:long "cov-nop" :toggle? true :plugin :kaocha.plugin/cloverage :doc "Ignored by volley (cloverage)."}
   {:long "cov-test-ns-regex" :arg "REGEX" :multi? true :plugin :kaocha.plugin/cloverage
    :doc "Ignored by volley (cloverage)."}
   {:long "cov-ns-regex" :arg "REGEX" :multi? true :plugin :kaocha.plugin/cloverage
    :doc "Ignored by volley (cloverage)."}
   {:long "cov-ns-exclude-regex" :arg "REGEX" :multi? true :plugin :kaocha.plugin/cloverage
    :doc "Ignored by volley (cloverage)."}
   {:long "cov-src-ns-path" :arg "PATH" :multi? true :plugin :kaocha.plugin/cloverage
    :doc "Ignored by volley (cloverage)."}
   {:long "cov-exclude-call" :arg "NAME" :multi? true :plugin :kaocha.plugin/cloverage
    :doc "Ignored by volley (cloverage)."}
   ;; volley's own
   {:long "workers" :volley? true :arg "N" :parse parse-pos-int
    :doc "Number of parallel worker processes."}
   {:long "preload" :toggle? true :volley? true
    :doc "Load every test namespace in each worker before running any, as Kaocha does."}
   {:long "isolate" :toggle? true :volley? true
    :doc "Run every namespace in a fresh worker process."}
   {:long "runtime" :arg "RUNTIME" :volley? true :parse parse-runtime
    :doc "Worker runtime: bb or jvm (default: the runtime volley runs on)."}
   {:long "aliases" :arg "ALIASES" :volley? true :parse runtime/parse-aliases
    :doc "deps.edn aliases for the classpath of --runtime jvm from babashka (default: :test if defined)."}])

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

      ;; tools.cli's #"^-." : a dash before a line break is an argument
      (re-find #"^-." token)
      (let [[state' rest'] (short-option state (subs token 1) more)]
        (recur state' rest'))

      :else
      (recur (update state :arguments conj token) more))))

(def ^:private plugin-flags
  "Option key -> the plugin that defines the flag."
  (into {} (keep (fn [spec] (when (:plugin spec) [(spec-key spec) spec]))) option-specs))

(defn plugin-flag-errors
  "Kaocha only knows a plugin's flags when the plugin is loaded, and rejects
  them otherwise (\"Unknown option\", exit -1). `options` are parsed
  options, `plugins` the normalized plugin keywords of the config. Returns
  the errors for flags whose plugin is not listed."
  [options plugins]
  (let [on (set plugins)]
    (vec (for [[k _] (sort-by (comp str key) options)
               :let [spec (get plugin-flags k)]
               :when (and spec (not (contains? on (:plugin spec))))]
           (str "Unknown option: \"--" (:long spec) "\" (it needs " (:plugin spec) " in :plugins)")))))

(defn- format-row [{:keys [short long arg toggle? default doc]}]
  [(str (if short (str short ", ") "    ")
        "--" (if toggle? (str "[no-]" long) long)
        (when arg (str " " arg)))
   (some-> default str)
   doc])

(def ^:private help-sections
  "The groups `--help` lists options in, in order."
  [[nil (fn [spec] (not (or (:plugin spec) (:volley? spec))))]
   ["Options of Kaocha plugins (only when the plugin is in :plugins or given with --plugin):"
    :plugin]
   ["volley's own options:" :volley?]])

(defn help-text
  "Usage text for `--help`, in the shape of Kaocha's own, with plugin
  options and volley's own under headings. `invocation` is how volley was
  run (`volley.runtime/invocation`)."
  [invocation]
  (let [all (map format-row option-specs)
        w1 (apply max (map (comp count first) all))
        w2 (apply max (map (comp count #(or % "") second) all))
        pad (fn [s w] (str s (apply str (repeat (- w (count (or s ""))) \space))))
        row (fn [[flag default doc]] (str "  " (pad flag w1) "  " (pad default w2) "  " doc))]
    (str "USAGE:\n\n"
         invocation " [OPTIONS]... [TEST-SUITE]...\n\n"
         (->> help-sections
              (map (fn [[heading pred]]
                     (str (when heading (str heading "\n"))
                          (str/join "\n" (map (comp row format-row) (filter pred option-specs))))))
              (str/join "\n\n"))
         "\n\nOptions may be repeated multiple times for a logical OR effect.")))

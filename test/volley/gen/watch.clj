(ns volley.gen.watch
  "Generators for `volley.watch`: snapshots, key sequences, change bursts,
  rerun selections and the configs and options of a watch run."
  (:require
   [volley.config :as config]
   [volley.gen.compat :as g]
   [volley.watch :as watch]
   [clojure.test.check.generators :as gen]))

(def path
  "A watched path, a test or source file or tests.edn."
  (gen/elements ["src/a.clj" "src/b.clj" "src/c.cljc" "test/a_test.clj" "test/b_test.clj"
                         "resources/x.edn" "tests.edn"]))

(def snapshot
  "`{path mtime}`; mtime 0 is included on purpose, it is falsy-looking."
  (gen/map path (gen/choose 0 3) {:max-elements 7}))

(def key-char
  "Keys a user could press, command keys over-represented."
  (gen/frequency [[8 (gen/elements [\a \f \p \t \c \h \q \return \newline \u001b])]
                  [3 (gen/elements [\backspace \u007f \u0003])]
                  [6 (gen/elements [\x \m \( \[ \space \. \* \1])]]))

(def key-input
  "A key press."
  (gen/fmap (fn [c] {:event :key :char c}) key-char))

(def outcome-input
  "A finished run's outcome, as the shell reports it."
  (gen/let [failed (gen/map (gen/elements '[a.core-test b.core-test c.core-test])
                            (gen/set (gen/elements '[x y z]) {:max-elements 2})
                            {:max-elements 3})
            none? (gen/frequency [[5 (gen/return false)] [1 (gen/return true)]])]
    {:event :run/finished :empty? none?
     :outcome {:ran (set (keys failed)) :failed failed}}))

(def files-input
  "A file change."
  (gen/let [paths (gen/vector path 1 3)
            affected (gen/set (gen/elements '[a.core-test b.core-test]) {:max-elements 2})]
    {:event :files-changed :paths paths :affected affected}))

(def any-input
  "Any input the state machine accepts, with times to be filled in."
  (gen/frequency [[10 key-input] [2 files-input] [2 outcome-input] [3 (gen/return {:event :tick})]]))

(def inputs
  "A sequence of inputs with non-decreasing `:at` times."
  (gen/let [raw (gen/vector any-input 0 40)
            gaps (gen/vector (gen/choose 0 400) 40)]
    (vec (map (fn [i _gap t] (assoc i :at t))
              raw gaps
              (rest (reductions + 0 gaps))))))

(def timeline
  "A change burst timeline: a vector of `{:at ms :paths [..] :affected #{..}}`.
  Gaps inside a burst are shorter than the debounce window; gaps between
  bursts are much longer than it."
  (gen/let [bursts (gen/vector
                    (gen/let [n (gen/choose 1 5)
                              inner (gen/vector (gen/choose 1 (- watch/debounce-ms 50)) n)
                              ps (gen/vector path n)
                              as (gen/vector (gen/elements '[a.core-test b.core-test c.core-test]) n)
                              between (gen/choose (+ watch/debounce-ms 300) 2000)]
                      {:inner inner :paths ps :affected as :between between})
                    1 5)]
    (:out (reduce (fn [{:keys [t out]} [burst {:keys [inner paths affected between]}]]
                    (let [times (rest (reductions + t inner))]
                      {:t (+ (last times) between)
                       :out (into out (map (fn [at p a] {:at at :burst burst :paths [p] :affected #{a}})
                                           times paths affected))}))
                  {:t 100 :out []}
                  (map-indexed vector bursts)))))

(def selection
  "A rerun selection over the namespaces of `g/corpus` is built in tests;
  this one only picks flags and patterns."
  (gen/let [all? gen/boolean
            failed? gen/boolean
            ns-pattern (gen/elements [nil "a" "b" "." "x$" "^a" "zzz"])
            test-pattern (gen/elements [nil "a" "b" "." "foo" "zzz"])]
    {:all? all? :failed? failed? :ns-pattern ns-pattern :test-pattern test-pattern}))

(def project
  "A corpus of test namespace infos by path, a config, a selection, and a
  failed map."
  (gen/let [corpus g/corpus
            sel selection
            picks (gen/vector gen/nat 0 4)
            failed-picks (gen/vector gen/nat 0 3)
            skip (g/filter-gen corpus)]
    (let [nses (mapv :ns corpus)
          pick (fn [ns] (into #{} (map #(nth nses (mod % (count nses)))) ns))
          failed (into {} (map (fn [ns] [ns (set (take 1 (keys (:vars (first (filter #(= ns (:ns %)) corpus))))))]))
                       (pick failed-picks))]
      {:corpus corpus
       :infos (into {} (map (juxt :file identity)) corpus)
       :config (g/config-for {} {:skip (:skip skip)})
       :selection (assoc sel :namespaces (pick picks))
       :failed failed})))

(def ^:private failed-map
  "`{ns #{var}}` over a few namespaces; an empty set means the whole
  namespace."
  (gen/map (gen/elements '[a-test b-test c-test])
           (gen/set (gen/elements '[x y z]) {:max-elements 3})
           {:max-elements 3}))

(def outcome-pair
  "A previous merged picture and the `volley.watch/outcome` of a later run,
  whose `:skipped` vars are a subset of the namespaces it ran."
  (gen/let [prev failed-map
            ran (gen/set (gen/elements '[a-test b-test c-test]) {:max-elements 3})
            failed failed-map
            skipped failed-map]
    {:prev (watch/merge-outcome nil {:ran #{} :failed prev})
     :run {:ran (into ran (keys failed))
           :failed failed
           :skipped (into {} (filter (comp (into ran (keys failed)) key)) skipped)}}))

(def reloaded-config
  "A normalized config as a tests.edn reload gives it: raw tests.edn data,
  perhaps with a randomize seed, and a session seed in the env."
  (gen/let [raw g/raw-config
            seed (gen/elements [nil 0 99 1234])
            env-seed gen/nat]
    (config/normalize (cond-> raw seed (assoc :kaocha.plugin.randomize/seed seed))
                      {} {:seed env-seed})))

(def engine-opts
  "Session `volley.engine/init` options, with stale seed and hook entries."
  (gen/let [base (gen/map (gen/elements [:workers :fail-fast? :isolate? :bindings :junit-xml])
                          gen/small-integer {:max-elements 4})
            stale (gen/map (gen/elements [:seed :configure :post-summary])
                           gen/small-integer {:max-elements 3})]
    (merge base stale)))

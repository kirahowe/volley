(ns brisk.cli-test
  (:require
   [brisk.cli :as cli]
   [brisk.gen.compat :as g]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

;; ---------------------------------------------------------------------------
;; Properties

(defspec parse-args-is-total 300
  (prop/for-all [argv g/argv]
    (let [{:keys [options suites errors]} (cli/parse-args argv)]
      (and (map? options)
           (vector? suites)
           (every? keyword? suites)
           (vector? errors)
           (every? string? errors)))))

(defspec parse-args-is-total-on-arbitrary-strings 200
  (prop/for-all [argv (gen/vector gen/string 0 8)]
    (map? (cli/parse-args argv))))

(defspec plain-words-are-suites 200
  (prop/for-all [words (gen/vector g/segment 0 5)]
    (let [{:keys [suites errors]} (cli/parse-args words)]
      (and (empty? errors)
           (= (mapv keyword (distinct words)) suites)))))

(defspec repeatable-flags-accumulate-in-order 200
  (prop/for-all [flag (gen/elements ["--focus" "--skip" "--focus-meta" "--skip-meta"])
                 vals (gen/vector g/segment 1 5)]
    (let [argv (mapcat (fn [v] [flag v]) vals)
          {:keys [options errors]} (cli/parse-args argv)]
      (and (empty? errors)
           (= (mapv keyword vals) (get options (keyword (subs flag 2))))))))

(defspec leading-colon-is-optional 200
  (prop/for-all [flag (gen/elements ["--focus" "--skip" "--focus-meta" "--skip-meta"])
                 v g/segment]
    (= (cli/parse-args [flag v]) (cli/parse-args [flag (str ":" v)]))))

(defspec last-toggle-wins 200
  (prop/for-all [flag (gen/elements ["color" "fail-fast" "watch" "capture-output" "isolate"])
                 states (gen/vector gen/boolean 1 5)]
    (let [argv (map #(if % (str "--" flag) (str "--no-" flag)) states)
          {:keys [options errors]} (cli/parse-args argv)]
      (and (empty? errors)
           (= (last states) (get options (keyword flag)))))))

(defspec every-unknown-long-flag-is-an-error 200
  (prop/for-all [name (gen/fmap #(str "zz-" %) g/segment)]
    (let [{:keys [errors]} (cli/parse-args [(str "--" name)])]
      (= [(str "Unknown option: \"--" name "\"")] errors))))

(defspec after-double-dash-everything-is-a-suite 200
  (prop/for-all [head (gen/vector g/segment 0 3)
                 tail (gen/vector (gen/one-of [g/segment (gen/fmap #(str "--" %) g/segment)]) 1 4)]
    (let [{:keys [suites]} (cli/parse-args (concat head ["--"] tail))]
      (every? (set suites) (map keyword tail)))))

;; ---------------------------------------------------------------------------
;; Examples (kaocha.runner/cli-options, tools.cli behaviour)

(deftest defaults-test
  (is (= {:options {:config-file "tests.edn"} :suites [] :errors []}
         (cli/parse-args []))
      "kaocha.runner: --config-file defaults to tests.edn"))

(deftest suites-and-filters-test
  (testing "kaocha.plugin.filter cli-options: keyword parse-fn, accumulate"
    (is (= {:options {:config-file "tests.edn"
                      :focus [:my.ns-test/foo :my.ns-test]
                      :skip-meta [:slow :flaky]
                      :focus-meta [:fast]}
            :suites [:unit :integration]
            :errors []}
           (cli/parse-args ["unit" "--focus" "my.ns-test/foo" "--focus" ":my.ns-test"
                            "--skip-meta" ":slow" "--skip-meta" "flaky"
                            "--focus-meta" "fast" ":integration"])))))

(deftest boolean-toggles-test
  (testing "kaocha.runner: --[no-]fail-fast, --[no-]color, --[no-]watch"
    (is (= {:fail-fast true :color false :watch true}
           (-> (cli/parse-args ["--fail-fast" "--no-color" "--watch"])
               :options (dissoc :config-file))))
    (is (= {:fail-fast false}
           (-> (cli/parse-args ["--no-fail-fast"]) :options (dissoc :config-file))))))

(deftest reporter-and-plugin-qualification-test
  (testing "kaocha.runner --reporter: bare names live in kaocha.report"
    (is (= ['kaocha.report/documentation 'my.ns/reporter]
           (:reporter (:options (cli/parse-args ["--reporter" "documentation"
                                                 "--reporter" "my.ns/reporter"]))))))
  (testing "kaocha.runner --plugin: bare names live in kaocha.plugin"
    (is (= [:kaocha.plugin/notifier :my/plugin]
           (:plugin (:options (cli/parse-args ["--plugin" "notifier" "--plugin" ":my/plugin"])))))))

(deftest value-flags-test
  (let [opts #(:options (cli/parse-args %))]
    (is (= "ci.edn" (:config-file (opts ["-c" "ci.edn"]))))
    (is (= "ci.edn" (:config-file (opts ["--config-file=ci.edn"]))))
    (is (= "ci.edn" (:config-file (opts ["-cci.edn"]))) "tools.cli: -cVALUE")
    (is (= :ci (:profile (opts ["--profile" ":ci"]))))
    (is (= :deep (:diff-style (opts ["--diff-style" "deep"]))))
    (is (= 42 (:seed (opts ["--seed" "42"]))))
    (is (true? (:help (opts ["--help"]))))
    (is (true? (:test-help (opts ["-H"]))))
    (is (true? (:print-config (opts ["--print-config"]))))
    (is (true? (:print-test-plan (opts ["--print-test-plan"]))))))

(deftest brisk-flags-test
  (let [opts #(:options (cli/parse-args %))]
    (is (= 4 (:workers (opts ["--workers" "4"]))))
    (is (true? (:isolate (opts ["--isolate"]))))
    (is (= :jvm (:runtime (opts ["--runtime" "jvm"]))))
    (is (= :bb (:runtime (opts ["--runtime=bb"]))))))

(deftest errors-test
  (testing "tools.cli error messages, as printed by kaocha.runner"
    (is (= ["Unknown option: \"--bogus\""]
           (:errors (cli/parse-args ["--bogus"]))))
    (is (= ["Unknown option: \"-x\""]
           (:errors (cli/parse-args ["-x"]))))
    (is (= ["Missing required argument for \"--focus SYM\""]
           (:errors (cli/parse-args ["--focus"]))))
    (is (= ["Missing required argument for \"-c FILE\""]
           (:errors (cli/parse-args ["--color" "-c"])))))
  (testing "unknown options do not swallow the next token (it becomes a suite)"
    (let [{:keys [suites errors]} (cli/parse-args ["--foc" "a"])]
      (is (= [:a] suites))
      (is (= 1 (count errors)))))
  (testing "brisk's validated flags"
    (is (seq (:errors (cli/parse-args ["--workers" "0"]))))
    (is (seq (:errors (cli/parse-args ["--workers" "many"]))))
    (is (seq (:errors (cli/parse-args ["--runtime" "node"]))))
    (is (seq (:errors (cli/parse-args ["--seed" "x"]))))))

(deftest tools-cli-tokenizing-test
  (testing "a required value is taken even when it looks like a flag"
    (is (= [:--color] (:focus (:options (cli/parse-args ["--focus" "--color"]))))))
  (testing "bundled short flags"
    (let [{:keys [options errors]} (cli/parse-args ["-Hc" "x.edn"])]
      (is (empty? errors))
      (is (= {:test-help true :config-file "x.edn"} options))))
  (testing "a lone dash is a suite, `--` ends options"
    (is (= [:-] (:suites (cli/parse-args ["-"]))))
    (is (= [:--focus :a] (:suites (cli/parse-args ["--" "--focus" "a"])))))
  (testing "flags ignore =value, as tools.cli does"
    (is (true? (:color (:options (cli/parse-args ["--color=1"])))))))

(deftest help-text-test
  (let [text (cli/help-text "bin/brisk")]
    (is (str/starts-with? text "USAGE:\n\nbin/brisk [OPTIONS]... [TEST-SUITE]..."))
    (is (not (str/includes? text "Defaults to true")) "colour depends on the terminal")
    (doseq [flag ["--config-file" "--print-config" "--print-test-plan" "--[no-]fail-fast"
                  "--[no-]color" "--[no-]watch" "--reporter" "--plugin" "--focus"
                  "--skip" "--focus-meta" "--skip-meta" "--[no-]capture-output"
                  "--workers" "--[no-]isolate" "--runtime" "--help"]]
      (is (str/includes? text flag) flag))))

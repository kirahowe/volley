(ns volley.e2e.reporting-test
  "volley as a real subprocess on the fixture projects in
  dev-resources/projects. Reporters, colour, uncaptured output and the
  junit-xml report."
  (:require
   [babashka.fs :as fs]
   [volley.e2e.support :as e2e]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest colour-and-reporters
  (testing "--color forces colour, NO_COLOR wins over it"
    (let [dir (e2e/copy-project "default")]
      (is (str/includes? (:out (e2e/volley dir ["--color"])) "\u001b["))
      (is (not (str/includes? (:out (e2e/volley dir ["--color"] {:env {"NO_COLOR" "1"}})) "\u001b[")))))
  (testing "--reporter documentation: one line per var"
    (let [{:keys [out]} (e2e/run "default" ["--reporter" "documentation"])]
      (is (str/includes? out "acme.math-test"))
      (is (re-find #"(?m)^  ✓ adds \d+ms$" out))
      (is (re-find #"(?m)^  ✗ wrong \d+ms$" out))))
  (testing "--reporter dots and progress map to the CI output without a warning"
    (doseq [r ["dots" "kaocha.report.progress/report"]]
      (let [{:keys [out err]} (e2e/run "default" ["--reporter" r])]
        (is (not (str/includes? err "reporter")))
        (is (some? (e2e/summary-line out)))))))

(deftest a-project-reporter-gets-every-event
  (testing "a reporter fn, and a var holding a vector of them, run in the workers"
    (doseq [r ["rp.reporter/report" "rp.reporter/both"]]
      (let [{:keys [exit out]} (e2e/run "reporters" ["--reporter" r])]
        (is (= 0 exit))
        (is (str/includes? out "REPORTED one"))
        (is (str/includes? out "REPORTED two")))))
  (testing ":kaocha/reporter in tests.edn"
    (let [{:keys [out]} (e2e/run "reporters" [] {"tests.edn" "#kaocha/v1 {:reporter [rp.reporter/report]}"})]
      (is (str/includes? out "REPORTED one"))))
  (testing "kaocha.api/resolve-reporter: a reporter that does not resolve exits -2"
    (let [{:keys [exit err]} (e2e/run "reporters" ["--reporter" "rp.reporter/nope"])]
      (is (= 254 exit))
      (is (str/includes? err "Failed to resolve reporter var: rp.reporter/nope")))))

(deftest junit-xml-reports-like-kaocha-junit-xml
  (testing "--plugin and --junit-xml-file on the command line"
    (let [dir (e2e/copy-project "default")
          {:keys [exit]} (e2e/volley dir ["--plugin" "kaocha.plugin/junit-xml" "--junit-xml-file" "target/junit.xml"])
          xml (slurp (str (fs/path dir "target/junit.xml")))]
      (is (= 1 exit))
      (is (str/includes? xml "<testsuite name=\"unit\""))
      (is (str/includes? xml "<testcase name=\"acme.strings-test/wrong\" classname=\"acme.strings-test\""))
      (is (str/includes? xml "<failure message=\"[:fail] expected:"))))
  (testing "the plugin and :kaocha.plugin.junit-xml/target-file in tests.edn"
    (let [dir (e2e/copy-project "default" {"tests.edn" (str "#kaocha/v1 {:plugins [:kaocha.plugin/junit-xml]"
                                                            " :kaocha.plugin.junit-xml/target-file \"out/j.xml\"}")})
          {:keys [exit err]} (e2e/volley dir [])]
      (is (= 1 exit))
      (is (not (str/includes? err "junit")) "no warning about the plugin")
      (is (fs/exists? (fs/path dir "out/j.xml")))))
  (testing "a plugin's flag without the plugin is an unknown option, as in Kaocha"
    (let [{:keys [exit out]} (e2e/run "default" ["--junit-xml-file" "j.xml"])]
      (is (= 255 exit))
      (is (str/includes? out "Unknown option: \"--junit-xml-file\""))))
  (testing "flags of a listed plugin volley does not run are accepted, under one warning"
    (let [{:keys [exit err]} (e2e/run "default" ["--plugin" "cloverage" "--plugin" "notifier" "--cov-output" "cov"
                                             "--no-cov-html" "--no-notifications"])]
      (is (= 1 exit))
      (is (= 1 (count (re-seq #"volley does not run these plugins" err))))
      (is (str/includes? err ":kaocha.plugin/cloverage, :kaocha.plugin/notifier"))))
  (testing "kaocha.plugin/register: a plugin no namespace defines exits 248"
    (let [{:keys [exit err]} (e2e/run "default" ["--plugin" "my.team/thing"])]
      (is (= 248 exit))
      (is (str/includes? err "ERROR: Couldn't load plugin :my.team/thing. Failed to load namespaces my.team.thing and my.team."))))
  (testing "kaocha.plugin/register: a namespace that loads but defines no plugin exits 249"
    (let [dir (e2e/copy-project "default" {"src/my/team/thing.clj" "(ns my.team.thing)"})
          {:keys [exit err]} (e2e/volley dir ["--plugin" "my.team/thing"])]
      (is (= 249 exit))
      (is (str/includes? err "ERROR: Couldn't load plugin :my.team/thing. The plugin was not defined after loading namespace my.team.thing. Is the file missing a defplugin?"))))
  (testing "a plugin defined in a namespace the plugin's namespace requires runs with a warning"
    (let [dir (e2e/copy-project "default" {"src/my/team/thing.clj" "(ns my.team.thing (:require [my.team.impl]))"
                                           "src/my/team/impl.clj" "(ns my.team.impl (:require [kaocha.plugin :refer [defplugin]]))\n(defplugin my.team/thing)"})
          {:keys [exit err]} (e2e/volley dir ["--plugin" "my.team/thing"])]
      (is (= 1 exit) "the project's one failing test")
      (is (not (str/includes? err "ERROR:")))
      (is (str/includes? err ":my.team/thing")))))

(deftest output-outside-capture-is-printed-as-it-happens
  (testing "--no-capture-output: everything a test prints shows, as in Kaocha"
    (let [{:keys [exit out]} (e2e/run "no-capture" ["--no-capture-output"])]
      (is (= 0 exit))
      (is (str/includes? out "printed at load time"))
      (is (str/includes? out "debug print from a passing test"))))
  (testing "with capture, a passing test's output stays hidden but load-time prints show"
    (let [{:keys [out]} (e2e/run "no-capture" [])]
      (is (str/includes? out "printed at load time"))
      (is (not (str/includes? out "debug print from a passing test"))))))

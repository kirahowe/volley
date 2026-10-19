(ns volley.diff.kaocha-e2e
  "Differential end-to-end tests: Kaocha itself (`bb -m kaocha.runner/-main`)
  and volley on the same fixture projects with the same arguments must run
  the same test vars with the same outcome and assertion counts, and exit
  with the same code. Needs Kaocha on the classpath; run with `bb test:diff`.

  Projects whose behaviour differs on purpose (worker crashes, load errors,
  fail-fast inside a var) are listed under Decisions in doc/design.md and
  checked here for exactly that difference."
  (:require
   [babashka.fs :as fs]
   [volley.diff.kaocha-result :as kr]
   [volley.e2e.support :as e2e]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- kaocha-in
  "Run Kaocha in project directory `dir`; returns `[exit summary]` where
  summary is what `volley.diff.kaocha-result` printed."
  [dir args]
  (let [cp (e2e/classpath (e2e/parent-classpath) "src")
        {:keys [exit out err]} (e2e/sh dir (into ["bb" "-cp" cp "-m" "kaocha.runner/-main"
                                                  "--plugin" "volley.diff.kaocha-result/plugin"]
                                                 args))]
    [exit (or (e2e/last-edn-line out kr/marker true)
              (throw (ex-info "Kaocha printed no result" {:out out :err err})))]))

(defn- kaocha
  "`kaocha-in` a copy of `project`."
  [project args]
  (kaocha-in (e2e/copy-project project) args))

(defn- volley-in
  [dir args]
  (let [{:keys [exit out err]} (e2e/volley dir (conj (vec args) "--print-result"))]
    [exit (or (e2e/print-result out)
              (throw (ex-info "volley printed no result" {:out out :err err})))]))

(defn- volley
  [project args]
  (volley-in (e2e/copy-project project) args))

(defn- comparable
  "Executed vars with status and counts, plus the pending ones."
  [{:keys [vars]}]
  (into (sorted-map)
        (keep (fn [[v r]]
                (cond
                  (= :not-run (:status r)) nil
                  (not= :skipped (:status r)) [v (select-keys r [:status :pass :fail :error])]
                  (= :pending (:reason r)) [v :pending])))
        vars))

(def ^:private ns-pending-projects
  "Projects with `^:kaocha/pending` on an ns form, which Kaocha counts as
  one pending test and volley as one per var (doc/design.md, divergences)."
  #{"pending"})

(defn- same-run? [project args]
  (let [[kexit kres] (kaocha project args)
        [bexit bres] (volley project args)]
    (testing (str project " " args)
      (is (seq (comparable kres)) "Kaocha ran something")
      (is (= (comparable kres) (comparable bres)))
      (when-not (contains? ns-pending-projects project)
        (is (= (:totals kres) (:totals bres)) "test and pending counts of the summary line"))
      (is (= kexit bexit) "exit code"))))

(deftest same-tests-same-results
  (doseq [[project args] [["default" []]
                          ["suites" []]
                          ["suites" ["unit"]]
                          ["suites" [":integration"]]
                          ["skipped-suite" []]
                          ["bindings" []]
                          ["filters" []]
                          ["filters" ["--focus" "filters.core-test/plain"]]
                          ["filters" ["--focus" "filters.other-test"]]
                          ["filters" ["--focus-meta" ":wip"]]
                          ["filters" ["--skip-meta" "flaky"]]
                          ["filters" ["--skip" "filters.other-test"]]
                          ["filters" ["--focus-meta" "wip" "--skip" "filters.meta-ns-test"]]
                          ["pending" []]
                          ["serial" []]
                          ["failures" []]
                          ["failures" ["--focus" "shop.errors-test"]]
                          ["print-vars" []]
                          ["generated" []]
                          ["generated" ["--focus" "g.gen-test/generated"]]
                          ["generated" ["--focus-meta" ":integration"]]
                          ["generated" ["--focus-meta" ":integration" "--focus-meta" ":slow"]]
                          ["generated" ["--focus-meta" ":nobody"]]
                          ["generated" ["--focus" "g.plain-test" "--focus" "g.gen-test"]]
                          ["generated" ["--skip-meta" ":integration"]]
                          ;; kaocha.type.var: a var with no assertion fails
                          ["zero-assertions" []]]]
    (same-run? project args)))

(deftest once-fixtures-of-an-all-pending-namespace-run-like-kaocha
  (testing "kaocha.testable/run-testable runs a namespace whose vars are all pending, but not a pending namespace"
    (let [kdir (e2e/copy-project "pending-fixtures")
          bdir (e2e/copy-project "pending-fixtures")
          [kexit kres] (kaocha-in kdir [])
          [bexit bres] (volley-in bdir [])
          ran (fn [dir] (set (for [f ["all-pending.ran" "whole-ns.ran"] :when (fs/exists? (fs/path dir f))] f)))]
      (is (= #{"all-pending.ran"} (ran kdir) (ran bdir)) "which :once fixtures ran")
      (is (= (comparable kres) (comparable bres)))
      (is (= 0 kexit bexit)))))

(deftest fail-fast-stops-after-the-failing-var-like-kaocha
  (testing "kaocha.testable/run-testables stops at the first failed var; so does volley's worker"
    (let [[kexit kres] (kaocha "fail-fast" ["--no-randomize"])
          [bexit bres] (volley "fail-fast" ["--workers" "1" "--no-randomize"])
          ran-before {'ff.a-fails-test/after-the-failure {:status :passed :pass 1 :fail 0 :error 0}
                      'ff.a-fails-test/first-failure {:status :failed :pass 0 :fail 1 :error 0}}]
      (is (= 1 kexit bexit))
      (is (= ran-before (comparable kres) (comparable bres))))))

(deftest load-error-divergence
  (testing "Kaocha fails the whole suite on one unloadable namespace; volley runs the rest"
    (let [[kexit kres] (kaocha "load-errors" [])
          [bexit bres] (volley "load-errors" [])]
      (is (= {} (comparable kres)))
      (is (= [:unit] (:load-errors kres)))
      (is (= 1 kexit))
      (is (= 3 bexit) "missing require, fixture error, unreadable file")
      (is (= #{'shop.passing-test/one 'shop.passing-test/two} (set (keys (comparable bres))))))))

(deftest exit-zero-divergence
  (testing "System/exit 0 in a test ends Kaocha's process with 0; volley reports a crash"
    (let [dir (e2e/copy-project "exit-zero")
          cp (e2e/classpath (e2e/parent-classpath) "src")
          {kexit :exit} (e2e/sh dir ["bb" "-cp" cp "-m" "kaocha.runner/-main" "--no-randomize"])
          [bexit bres] (volley "exit-zero" [])]
      (is (= 0 kexit))
      (is (= 1 bexit))
      (is (= :crashed (get-in bres [:namespaces 'ez.exits-test])))
      (is (= :passed (get-in bres [:namespaces 'ez.survivor-test]))))))

(deftest once-fixture-assertion-divergence
  (testing "Kaocha prints a failing assertion in a :once fixture but leaves it out of the exit code; volley counts it"
    (let [[kexit kres] (kaocha "fixture-assertions" [])
          [bexit bres] (volley "fixture-assertions" [])]
      (is (= (comparable kres) (comparable bres)) ":each fixture assertions count for the var in both")
      (is (= 2 kexit))
      (is (= 3 bexit)))))

(deftest plugin-load-errors-match-kaocha
  (testing "kaocha.plugin/register: same exit code and message for a plugin that does not load"
    (doseq [files [{"src/my/team/thing.clj" "(ns my.team.thing)"}
                   {"src/my/team.clj" "(ns my.team)"}
                   {}]]
      (let [dir (e2e/copy-project "default" files)
            cp (e2e/classpath (e2e/parent-classpath) "src")
            args ["--plugin" "my.team/thing"]
            k (e2e/sh dir (into ["bb" "-cp" cp "-m" "kaocha.runner/-main"] args))
            v (e2e/volley dir args)
            error-line #(some (fn [l] (when (str/starts-with? l "ERROR:") l))
                              (str/split-lines (str/replace (str (:out %) (:err %)) #"\u001b\[[0-9;]*m" "")))]
        (testing (pr-str files)
          (is (= (:exit k) (:exit v)) "exit code")
          (is (some? (error-line k)))
          (is (= (error-line k) (error-line v))))))))

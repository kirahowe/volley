(ns brisk.diff.kaocha-e2e
  "Differential end-to-end tests: Kaocha itself (`bb -m kaocha.runner/-main`)
  and brisk on the same fixture projects with the same arguments must run
  the same test vars with the same outcome and assertion counts, and exit
  with the same code. Needs Kaocha on the classpath; run with `bb test:diff`.

  Projects whose behaviour differs on purpose (worker crashes, load errors,
  fail-fast inside a var) are listed under Decisions in doc/design.md and
  checked here for exactly that difference."
  (:require
   [brisk.diff.kaocha-result :as kr]
   [brisk.e2e.support :as e2e]
   [clojure.test :refer [deftest is testing]]))

(defn- kaocha
  "Run Kaocha in a copy of `project`; returns `[exit summary]` where summary
  is what `brisk.diff.kaocha-result` printed."
  [project args]
  (let [dir (e2e/copy-project project)
        cp (e2e/classpath (e2e/parent-classpath) "src")
        {:keys [exit out err]} (e2e/sh dir (into ["bb" "-cp" cp "-m" "kaocha.runner/-main"
                                                  "--plugin" "brisk.diff.kaocha-result/plugin"]
                                                 args))]
    [exit (or (e2e/last-edn-line out kr/marker true)
              (throw (ex-info "Kaocha printed no result" {:out out :err err})))]))

(defn- brisk
  [project args]
  (let [{:keys [exit out err]} (e2e/brisk (e2e/copy-project project) (conj (vec args) "--print-result"))]
    [exit (or (e2e/last-edn-line out "{:vars")
              (throw (ex-info "brisk printed no result" {:out out :err err})))]))

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

(defn- same-run? [project args]
  (let [[kexit kres] (kaocha project args)
        [bexit bres] (brisk project args)]
    (testing (str project " " args)
      (is (seq (comparable kres)) "Kaocha ran something")
      (is (= (comparable kres) (comparable bres)))
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

(deftest fail-fast-stops-after-the-failing-var-like-kaocha
  (testing "kaocha.testable/run-testables stops at the first failed var; so does brisk's worker"
    (let [[kexit kres] (kaocha "fail-fast" ["--no-randomize"])
          [bexit bres] (brisk "fail-fast" ["--workers" "1"])
          ran-before {'ff.a-fails-test/after-the-failure {:status :passed :pass 1 :fail 0 :error 0}
                      'ff.a-fails-test/first-failure {:status :failed :pass 0 :fail 1 :error 0}}]
      (is (= 1 kexit bexit))
      (is (= ran-before (comparable kres) (comparable bres))))))

(deftest load-error-divergence
  (testing "Kaocha fails the whole suite on one unloadable namespace; brisk runs the rest"
    (let [[kexit kres] (kaocha "load-errors" [])
          [bexit bres] (brisk "load-errors" [])]
      (is (= {} (comparable kres)))
      (is (= [:unit] (:load-errors kres)))
      (is (= 1 kexit))
      (is (= 3 bexit) "missing require, fixture error, unreadable file")
      (is (= #{'shop.passing-test/one 'shop.passing-test/two} (set (keys (comparable bres))))))))

(deftest exit-zero-divergence
  (testing "System/exit 0 in a test ends Kaocha's process with 0; brisk reports a crash"
    (let [dir (e2e/copy-project "exit-zero")
          cp (e2e/classpath (e2e/parent-classpath) "src")
          {kexit :exit} (e2e/sh dir ["bb" "-cp" cp "-m" "kaocha.runner/-main" "--no-randomize"])
          [bexit bres] (brisk "exit-zero" [])]
      (is (= 0 kexit))
      (is (= 1 bexit))
      (is (= :crashed (get-in bres [:namespaces 'ez.exits-test])))
      (is (= :passed (get-in bres [:namespaces 'ez.survivor-test]))))))

(deftest once-fixture-assertion-divergence
  (testing "Kaocha prints a failing assertion in a :once fixture but leaves it out of the exit code; brisk counts it"
    (let [[kexit kres] (kaocha "fixture-assertions" [])
          [bexit bres] (brisk "fixture-assertions" [])]
      (is (= (comparable kres) (comparable bres)) ":each fixture assertions count for the var in both")
      (is (= 2 kexit))
      (is (= 3 bexit)))))

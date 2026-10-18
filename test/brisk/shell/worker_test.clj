(ns brisk.shell.worker-test
  "The worker run function in-process against the fixture namespaces in
  dev-resources/exec, compared with plain clojure.test."
  (:require
   [brisk.filters :as filters]
   [brisk.run-state :as rs]
   [brisk.shell.hooks :as hooks]
   [brisk.shell.worker :as worker]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is testing]]))

(defn- run-events
  "Run namespace `ns-sym` through the worker and return its events."
  ([ns-sym] (run-events ns-sym {}))
  ([ns-sym {:keys [decide capture-output? select fail-fast?]
            :or {decide worker/run-everything capture-output? true}}]
   (let [events (volatile! [])]
     (worker/run-assignment! #(vswap! events conj %) decide
                             (cond-> {:ns ns-sym :run-id 7 :select select
                                      :capture-output? capture-output?}
                               fail-fast? (assoc :fail-fast? true)))
     @events)))

(defn- counts [events]
  (->> events
       (filter #(= :var/end (:event %)))
       (reduce (fn [acc e] (merge-with + acc (select-keys e [:pass :fail :error])))
               {:pass 0 :fail 0 :error 0})))

(defn- run-tests-counts
  "What plain clojure.test (`test-ns`, which runs fixtures like
  `run-tests`) counts for `ns-sym`. It reports through its own `report`,
  so the counts stay separate from whatever runner is running this test."
  [ns-sym]
  (require ns-sym)
  (let [counts (atom {:pass 0 :fail 0 :error 0})]
    (binding [t/report (fn [m]
                         (when (contains? #{:pass :fail :error} (:type m))
                           (swap! counts update (:type m) inc)))]
      (t/test-ns ns-sym))
    @counts))

(deftest counts-match-clojure-test
  (doseq [ns-sym '[exec.pass-test exec.fail-test exec.error-test exec.fixtures-test
                   exec.contexts-test exec.skip-test exec.sync-test]]
    (testing (str ns-sym)
      (is (= (run-tests-counts ns-sym) (counts (run-events ns-sym)))))))

(deftest event-framing
  (let [events (run-events 'exec.pass-test)]
    (is (= {:event :ns/start :ns 'exec.pass-test :run-id 7} (first events)))
    (is (= [:ns/end 'exec.pass-test 7]
           ((juxt :event :ns :run-id) (last events))))
    (is (number? (:duration-ms (last events))))
    (is (= '[adds strings] (map :var (filter #(= :var/start (:event %)) events))))
    (is (every? #(and (string? (:file %)) (integer? (:line %)))
                (filter #(= :var/start (:event %)) events)))))

(deftest failing-assertion-carries-structured-values
  (let [events (run-events 'exec.fail-test)
        a (first (filter #(= :assertion (:event %)) events))]
    (is (= :fail (:type a)))
    (is (= 'nested-mismatch (:var a)))
    (is (= [] (:contexts a)))
    (testing "the nested data survives for structural diffing"
      (is (= '(= {:a [1 2 {:b 3}] :c #{1 2}} {:a [1 2 {:b 4}] :c #{1 2}})
             (get-in a [:expected :edn]))))
    (is (string? (get-in a [:actual :str])))
    (is (string? (:file a)))
    (is (= 5 (:line a)))
    (is (= {:pass 1 :fail 1 :error 0}
           (-> (filter #(= '[:var/end nested-mismatch] ((juxt :event :var) %)) events)
               first
               (select-keys [:pass :fail :error]))))))

(deftest erroring-test
  (let [events (run-events 'exec.error-test)
        errors (filter #(and (= :assertion (:event %)) (= :error (:type %))) events)]
    (is (= 2 (count errors)))
    (let [e (:error (first (filter #(= 'throws (:var %)) errors)))]
      (is (= "clojure.lang.ExceptionInfo" (:class e)))
      (is (= "boom" (:message e)))
      (is (= "{:why :testing}" (:data e))))
    (let [e (:error (first (filter #(= 'throws-inside-is (:var %)) errors)))]
      (is (= "java.lang.NumberFormatException" (:class e))))
    (testing "frames from the runner are trimmed"
      (is (not-any? #(re-find #"brisk\.|clojure\.test" %)
                    (mapcat (comp :trace :error) errors))))))

(deftest load-error
  (let [events (run-events 'exec.load-error-test)]
    (is (= [:ns/start :ns/error :ns/end] (map :event events)))
    ;; The JVM wraps the missing-namespace error in a CompilerException.
    (is (some #{"java.io.FileNotFoundException"}
              (map :class (take-while some? (iterate :cause (get-in events [1 :error]))))))
    (is (= 7 (:run-id (nth events 1))))))

(deftest fixture-failure-is-an-ns-error
  (let [events (run-events 'exec.once-error-test)]
    (is (= [:ns/start :var/skipped :ns/error :ns/end] (map :event events)))
    (is (= :fixture-failed (:reason (first (filter #(= :var/skipped (:event %)) events))))
        "the var the fixture kept from running is listed")
    (is (= "once fixture failed" (get-in events [2 :error :message])))))

(deftest once-and-each-fixtures-run-like-clojure-test
  (let [events (run-events 'exec.fixtures-test)]
    (is (= {:pass 3 :fail 0 :error 0} (counts events)))))

(deftest testing-contexts-and-custom-assert-expr
  (let [as (filter #(= :assertion (:event %)) (run-events 'exec.contexts-test))
        by-var (into {} (map (juxt :var identity)) as)]
    (is (= ["outer" "inner"] (:contexts (by-var 'nested-contexts))))
    (is (= "with message" (:message (by-var 'nested-contexts))))
    (is (= ":custom" (get-in (by-var 'custom-assert) [:actual :str])))))

(deftest decide-controls-which-vars-run
  (let [seen (volatile! [])
        decide (fn [select ns-sym _ns-meta var-sym var-meta]
                 (vswap! seen conj [select ns-sym var-sym (boolean (:slow var-meta))])
                 (if (:slow var-meta)
                   {:run? false :reason :skip-meta}
                   {:run? true :reason nil}))
        events (run-events 'exec.skip-test {:decide decide :select {:skip-meta [:slow]}})]
    (is (= '[[{:skip-meta [:slow]} exec.skip-test marked-slow true]
             [{:skip-meta [:slow]} exec.skip-test normal false]]
           @seen))
    (is (= [{:event :var/skipped :ns 'exec.skip-test :var 'marked-slow :reason :skip-meta}]
           (filter #(= :var/skipped (:event %)) events)))
    (is (= ['normal] (map :var (filter #(= :var/start (:event %)) events))))))

(deftest nothing-selected-runs-no-fixtures
  (let [events (run-events 'exec.fixtures-test
                           {:decide (fn [& _] {:run? false :reason :skip})})]
    (is (= [:ns/start :var/skipped :var/skipped :ns/end] (map :event events)))))

(deftest output-is-captured-per-var
  (let [events (run-events 'exec.output-test {:decide (fn [_ _ _ v _] {:run? (= v 'prints-via-println)})})
        end (first (filter #(= :var/end (:event %)) events))]
    (is (= "hello from println\nhello on err\n" (:output end)))
    (testing "without capture there is no :output"
      (is (nil? (:output (first (filter #(= :var/end (:event %))
                                        (binding [*out* (java.io.StringWriter.)
                                                  *err* (java.io.StringWriter.)]
                                          (run-events 'exec.output-test
                                                      {:capture-output? false
                                                       :decide (fn [_ _ _ v _] {:run? (= v 'prints-via-println)})}))))))))))

(deftest pure-helpers
  (is (= 1.5 (worker/duration-ms 1000000 2500000)))
  (is (= {:run [#'inc] :skipped [[#'dec :no]]}
         (worker/partition-vars (fn [_ _ _ v _] (if (= v 'inc) {:run? true} {:run? false :reason :no}))
                                nil 'x {} [#'inc #'dec]))
      "partition-vars hands decide the var's name and metadata"))

(defn- skip-spec
  "A `:select` spec as `brisk.select/plan` builds it, with an empty static
  copy of the namespace metadata so only the real metadata can matter."
  [pass]
  {:passes [(merge {:focus [] :skip [] :focus-meta [] :skip-meta [] :suite-focused? false} pass)]
   :ns-meta {}})

(deftest worker-uses-select-decide-with-real-ns-metadata
  (testing "kaocha.testable/run-testable: pending vars are skipped, skip-meta too, and only pending ones count"
    (let [events (run-events 'exec.pending-test {:decide filters/decide
                                                 :select (skip-spec {:skip-meta [:slow]})})
          state (reduce rs/apply-event (rs/init {})
                        (map #(assoc % :worker 0 :at 0) events))]
      (is (= '{not-written-yet :pending marked-slow :skip-meta}
             (into {} (keep (fn [e] (when (= :var/skipped (:event e)) [(:var e) (:reason e)]))) events)))
      (is (= {:total 2 :passed 1 :failed 0 :errored 0 :skipped 1 :running 0}
             (:vars (rs/summary state))))))
  (testing "^:kaocha/pending on the ns form is read from the loaded namespace"
    (let [events (run-events 'exec.ns-pending-test {:decide filters/decide :select (skip-spec {})})]
      (is (= [{:event :var/skipped :ns 'exec.ns-pending-test :var 'whole-ns-is-pending :reason :pending}]
             (filter #(= :var/skipped (:event %)) events))))))

(deftest a-failing-defspec-reports-its-check-at-the-var
  (let [events (run-events 'exec.property-test)
        a (first (filter #(= :assertion (:event %)) events))]
    (is (= :fail (:type a)))
    (is (= 42 (get-in a [:check :seed])))
    (is (= [3] (get-in a [:check :smallest :edn])) "the shrunk input")
    (is (pos-int? (get-in a [:check :num-tests])))
    (testing "the location is the var's file, never an interpreter file"
      ;; bb gives the path from the project root, the JVM from the classpath root.
      (is (str/ends-with? (:file a) "exec/property_test.clj"))
      (is (= 6 (:line a))))))

(deftest project-file-accepts-only-files-of-the-project
  (let [dir (System/getProperty "user.dir")]
    (is (worker/project-file? dir true "src/brisk/main.clj"))
    (is (worker/project-file? dir true (str dir "/src/brisk/main.clj")))
    (is (not (worker/project-file? dir true "reflector.cljc")))
    (is (not (worker/project-file? dir true "sci/impl/reflector.cljc")))
    (is (not (worker/project-file? dir true "/etc/hosts")))
    (is (not (worker/project-file? dir true "NO_SOURCE_PATH")))
    (testing "a bare name from a JVM stack frame is trusted"
      (is (worker/project-file? dir false "helper.clj")))))

(deftest fail-fast-stops-after-the-first-failing-var
  (let [started (fn [events] (map :var (filter #(= :var/start (:event %)) events)))]
    (is (= '[nested-mismatch passes] (started (run-events 'exec.fail-test))))
    (is (= '[nested-mismatch] (started (run-events 'exec.fail-test {:fail-fast? true}))))
    (testing "the :once fixtures still run around what did run"
      (is (= {:pass 3 :fail 0 :error 0} (counts (run-events 'exec.fixtures-test {:fail-fast? true})))))
    (is (= :ns/end (:event (last (run-events 'exec.fail-test {:fail-fast? true})))))))

(defn- sci-wrapper
  "What babashka's interpreter wraps around `cause` when it passes an
  interpreted `try`: an ExceptionInfo with the call stack in its data."
  [cause]
  (ex-info (ex-message cause) {:type :sci/error :sci.impl/callstack (volatile! nil)} cause))

(deftest interpreter-wrappers-are-dropped-anywhere-in-the-cause-chain
  (testing "a future's ExecutionException around a wrapper (probe variants2, w.trace-test/fut)"
    (let [e (java.util.concurrent.ExecutionException. (sci-wrapper (NumberFormatException. "For input string: \"y\"")))
          enc (worker/encode-throwable e)
          chain (take-while some? (iterate :cause enc))]
      (is (= ["java.util.concurrent.ExecutionException" "java.lang.NumberFormatException"] (map :class chain)))
      (is (= "java.lang.NumberFormatException: For input string: \"y\"" (:message enc)))
      (is (not-any? #(str/includes? (str (:data %)) "callstack") chain))))
  (testing "an error a hook wraps, itself wrapped (probe hooks, prerun.edn)"
    (let [e (sci-wrapper (ex-info "brisk: pre-run hook x/y threw" {:hook :pre-run}
                                  (sci-wrapper (ex-info "pre-run boom" {}))))
          chain (take-while some? (iterate :cause (worker/encode-throwable e)))]
      (is (= ["brisk: pre-run hook x/y threw" "pre-run boom"] (map :message chain)))
      (is (= ["{:hook :pre-run}" "{}"] (map :data chain))))))

(deftest a-hook-error-names-the-unwrapped-exception
  (let [hook '(fn [_] (throw (ex-info "npe" {:sci.impl/callstack (volatile! nil)} (NullPointerException. "npe"))))
        hs (-> (hooks/init {:hooks {:pre-run [hook]} :config {}})
               (hooks/setup :unit)
               (hooks/before-run :unit))]
    (is (str/ends-with? (ex-message (hooks/setup-error hs)) "threw java.lang.NullPointerException: npe"))))

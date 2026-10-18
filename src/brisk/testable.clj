(ns brisk.testable
  "Kaocha's data shapes for hooks: the testables of namespaces and vars,
  their result counts, and the test plan. Pure, and kept apart from
  `brisk.hooks` so that a worker can build them without loading the
  parent's run-state code.")

(defn ns-testable
  "The testable of namespace `ns-sym` before it is loaded
  (`kaocha.type.ns/->testable`)."
  [ns-sym]
  {:kaocha.testable/type :kaocha.type/ns
   :kaocha.testable/id (keyword (str ns-sym))
   :kaocha.testable/desc (str ns-sym)
   :kaocha.ns/name ns-sym})

(defn var-testable
  "The testable of test var `var-sym` in `ns-sym` with metadata
  `var-meta` (`kaocha.type.ns/-load`); the shell adds `:kaocha.var/var`
  and `:kaocha.var/test`."
  [ns-sym var-sym var-meta]
  (let [id (symbol (str ns-sym) (str var-sym))]
    {:kaocha.testable/type :kaocha.type/var
     :kaocha.testable/id (keyword id)
     :kaocha.testable/desc (str var-sym)
     :kaocha.testable/meta var-meta
     :kaocha.var/name id}))

(defn with-result
  "`testable` with Kaocha's result counts."
  [testable {:keys [pass fail error pending]}]
  (assoc testable
         :kaocha.result/count 1
         :kaocha.result/pass (or pass 0)
         :kaocha.result/fail (or fail 0)
         :kaocha.result/error (or error 0)
         :kaocha.result/pending (or pending 0)))

(defn test-plan
  "Kaocha's test plan shape: `kaocha-cfg` with `:kaocha/tests` replaced by
  `:kaocha.test-plan/tests` (or `result-key`) holding `suites`."
  ([kaocha-cfg suites] (test-plan kaocha-cfg suites :kaocha.test-plan/tests))
  ([kaocha-cfg suites result-key]
   (-> kaocha-cfg (dissoc :kaocha/tests) (assoc result-key (vec suites)))))

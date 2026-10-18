(ns brisk.shell.hooks
  "Kaocha hooks and `:brisk/worker-setup` inside a worker: resolves the
  hooks named in the parent's `{:op :configure}` message and calls them at
  the points `brisk.hooks/semantics` describes. The worker threads the
  value `init` returns through its loop; every function here takes it and
  returns the next one, so there is no state of its own.

  Portable Clojure, like `brisk.shell.worker`: it runs on babashka and on
  the JVM.

  A hook is a qualified symbol, resolved with `requiring-resolve` in the
  worker, or a list form, evaluated there (Kaocha's `load-hook`). The
  calling conventions are Kaocha's: `pre-load` gets the config, `post-load`
  and `pre-run` the test plan, `post-run` the result, `pre-load-test` and
  `post-load-test` a testable and the config, `pre-test` and `post-test` a
  testable and the test plan, `wrap-run` the run function, `pre-report` the
  report event. Each hook's return value is passed to the next hook of the
  same key, as Kaocha does; what the last one returns is ignored except
  for `pre-report` (the event that gets counted), `pre-test` (a testable
  with `:kaocha.testable/skip` skips a var) and `wrap-run` (the wrapped
  run function). `:brisk/worker-setup` fns take no arguments."
  (:require
   [brisk.hooks :as hooks]))

(set! *warn-on-reflection* true)

(defn- resolve-hook [entry]
  (cond
    (qualified-symbol? entry)
    (or (requiring-resolve entry)
        (throw (ex-info (str "hook " entry " does not exist") {:hook entry})))
    (seq? entry) (eval entry)
    :else (throw (ex-info (str "hook " (pr-str entry) " is not a symbol or a form") {:hook entry}))))

(defn- resolve-all [m]
  (update-vals m #(mapv resolve-hook %)))

(defn init
  "Hook state for the `{:op :configure}` message `msg`, or for none (nil):
  then every function here does nothing."
  [msg]
  {:msg msg
   :fns nil
   :started? false
   :ran? false
   :setup-error nil
   :suites-set-up #{}
   :suites-loaded #{}
   :suites-tested #{}
   :loaded {}
   :results {}})

(defn active?
  "True when there is anything to call."
  [hs]
  (some? (:msg hs)))

(defn setup-error
  "The Throwable a worker setup or run-level hook threw, or nil. Once set,
  the worker runs no more tests."
  [hs]
  (:setup-error hs))

(defn- hook-error
  "Wrap `e` so the report says which hook failed."
  [kind entry ^Throwable e]
  (ex-info (str "brisk: " (name kind) " hook " (pr-str entry) " threw "
                (.getName (class e)) (when-let [m (ex-message e)] (str ": " m)))
           {:hook kind :entry entry}
           e))

(defn- chain
  "Thread `x` through hooks `fs` (paired with their `entries` for error
  messages) as Kaocha's `(reduce #(%2 %1 ...) x hooks)` does."
  [kind fs entries x & args]
  (reduce (fn [acc [f entry]]
            (try (apply f acc args)
                 (catch ^:sci/error Throwable e (throw (hook-error kind entry e)))))
          x
          (map vector fs entries)))

(defn- top [hs kind] [(get-in hs [:fns :hooks kind]) (get-in hs [:msg :hooks kind])])
(defn- own [hs suite kind] [(get-in hs [:fns :suites suite :hooks kind]) (get-in hs [:msg :suites suite :hooks kind])])

(defn- run-chain [kind [fs entries] x & args]
  (apply chain kind fs entries x args))

(defn- config [hs] (get-in hs [:msg :config]))
(defn- suite-testable [hs suite] (get-in hs [:msg :testables suite] {:kaocha.testable/id suite}))

(defn plan
  "The test plan hooks see: the config with the namespaces this worker has
  loaded so far, under their suites."
  [hs]
  (hooks/test-plan (config hs)
                   (for [[suite nses] (:loaded hs)]
                     (assoc (suite-testable hs suite) :kaocha.test-plan/tests nses))))

(defn- result-plan [hs]
  (hooks/test-plan (config hs)
                   (for [[suite nses] (:results hs)]
                     (assoc (suite-testable hs suite) :kaocha.result/tests nses))
                   :kaocha.result/tests))

(defn- call-setup! [kind fs entries]
  (doseq [[f entry] (map vector fs entries)]
    (try (f)
         (catch ^:sci/error Throwable e (throw (hook-error kind entry e))))))

(defn- guard
  "Run `(f hs)`; a throw becomes the worker's setup error."
  [hs f]
  (try (f hs)
       (catch ^:sci/error Throwable e (assoc hs :setup-error e))))

(defn- start [hs]
  (let [{:keys [hooks worker-setup suites]} (:msg hs)
        fns {:hooks (resolve-all hooks)
             :worker-setup (mapv resolve-hook worker-setup)
             :suites (update-vals suites (fn [s] {:hooks (resolve-all (:hooks s))
                                                  :worker-setup (mapv resolve-hook (:worker-setup s))}))}
        hs (assoc hs :fns fns :started? true)]
    (call-setup! :worker-setup (:worker-setup fns) worker-setup)
    (run-chain :pre-load (top hs :pre-load) (config hs))
    hs))

(defn- start-suite [hs suite]
  (call-setup! :worker-setup (get-in hs [:fns :suites suite :worker-setup])
               (get-in hs [:msg :suites suite :worker-setup]))
  (as-> (suite-testable hs suite) t
    (run-chain :pre-load-test (top hs :pre-load-test) t (config hs))
    (run-chain :pre-load-test (own hs suite :pre-load-test) t (config hs)))
  (update hs :suites-set-up conj suite))

(defn setup
  "Before a namespace of `suite` is loaded: the first time in this worker,
  resolve every hook, call `:brisk/worker-setup` and `pre-load`; the first
  time for `suite`, its own worker setup and `pre-load-test` with the suite
  testable. A throw is kept as the setup error."
  [hs suite]
  (cond
    (or (not (active? hs)) (setup-error hs)) hs
    :else (guard hs (fn [hs]
                      (cond-> (if (:started? hs) hs (start hs))
                        (not (contains? (:suites-set-up hs) suite)) (start-suite suite))))))

(defn pre-load-ns
  "`pre-load-test` with the testable of `ns-sym`, before it is required.
  Throws when a hook does."
  [hs ns-sym]
  (when (active? hs)
    (run-chain :pre-load-test (top hs :pre-load-test) (hooks/ns-testable ns-sym) (config hs))))

(defn post-load-ns
  "After `ns-sym` loaded with test vars `vars`: `post-load-test` for it (and
  for the suite, the first time), then `post-load` with the plan so far.
  Returns the new state; throws when a hook does."
  [hs suite ns-sym ns-obj vars]
  (if-not (active? hs)
    hs
    (let [t (assoc (hooks/ns-testable ns-sym)
                   :kaocha.ns/ns ns-obj
                   :kaocha.testable/meta (meta ns-obj)
                   :kaocha.test-plan/tests (mapv #(hooks/var-testable ns-sym (:name (meta %)) (meta %)) vars))
          t (run-chain :post-load-test (top hs :post-load-test) t (config hs))
          hs (update-in hs [:loaded suite] (fnil conj []) t)
          hs (if (contains? (:suites-loaded hs) suite)
               hs
               (do (as-> (assoc (suite-testable hs suite) :kaocha.test-plan/tests (get-in hs [:loaded suite])) st
                     (run-chain :post-load-test (own hs suite :post-load-test) st (config hs))
                     (run-chain :post-load-test (top hs :post-load-test) st (config hs)))
                   (update hs :suites-loaded conj suite)))]
      (run-chain :post-load (top hs :post-load) (plan hs))
      hs)))

(defn before-run
  "Before the tests of a namespace of `suite`: `pre-run` the first time in
  this worker, and `pre-test` with the suite testable the first time for
  `suite`. A throw is kept as the setup error."
  [hs suite]
  (if (or (not (active? hs)) (setup-error hs))
    hs
    (guard hs (fn [hs]
                (let [hs (if (:ran? hs)
                           hs
                           (do (run-chain :pre-run (top hs :pre-run) (plan hs))
                               (assoc hs :ran? true)))]
                  (if (contains? (:suites-tested hs) suite)
                    hs
                    (do (as-> (suite-testable hs suite) t
                          (run-chain :pre-test (top hs :pre-test) t (plan hs))
                          (run-chain :pre-test (own hs suite :pre-test) t (plan hs)))
                        (update hs :suites-tested conj suite))))))))

(defn run-ns
  "Run a namespace: `pre-test` with its testable, `run` (a fn of no
  arguments returning the var results) wrapped by the `wrap-run` hooks,
  then `post-test` with the result. Returns `[hs result]`; throws when a
  hook does."
  [hs suite ns-sym ns-obj run]
  (if-not (active? hs)
    [hs (run)]
    (let [p (plan hs)
          t (assoc (hooks/ns-testable ns-sym) :kaocha.ns/ns ns-obj :kaocha.testable/meta (meta ns-obj))
          t (run-chain :pre-test (top hs :pre-test) t p)
          base (fn [testable _plan] (assoc testable :kaocha.result/tests (run)))
          wrapped (run-chain :wrap-run (top hs :wrap-run) base)
          r (wrapped t p)
          r (if (map? r) r (assoc t :kaocha.result/tests []))
          r (run-chain :post-test (top hs :post-test) r p)]
      [(update-in hs [:results suite] (fnil conj []) r) r])))

(defn run-var
  "Run test var `v` of `ns-sym`: `pre-test` with its testable, then
  `(test!)` unless a hook marked it `:kaocha.testable/skip`, then
  `(counts)` for its result and `post-test`. Returns the var's result
  testable, with `:kaocha.testable/skip` when it was skipped; throws when
  a hook does."
  [hs ns-sym v test! counts]
  (let [m (meta v)
        t (assoc (hooks/var-testable ns-sym (:name m) m) :kaocha.var/var v :kaocha.var/test (:test m))]
    (if-not (active? hs)
      (do (test!) (hooks/with-result t (counts)))
      (let [p (plan hs)
            t (run-chain :pre-test (top hs :pre-test) t p)]
        (if (:kaocha.testable/skip t)
          t
          (do (test!)
              (run-chain :post-test (top hs :post-test) (hooks/with-result t (counts)) p)))))))

(defn report-fn
  "`report` with the `pre-report` hooks in front: each event goes through
  them first. A hook that throws is reported on stderr and the event is
  counted unchanged."
  [hs report]
  (let [[fs entries] (top hs :pre-report)]
    (if (empty? fs)
      report
      (fn [m]
        (report (try (chain :pre-report fs entries m)
                     (catch ^:sci/error Throwable e
                       (binding [*out* *err*] (println (ex-message e)))
                       m)))))))

(defn shutdown
  "When the worker is shut down cleanly: `post-test` with each suite
  testable this worker ran, then `post-run` with the result. Returns the
  Throwables hooks threw (it calls every hook regardless)."
  [hs]
  (if-not (and (active? hs) (:started? hs) (not (setup-error hs)))
    []
    (let [p (plan hs)
          attempt (fn [f] (try (f) nil (catch ^:sci/error Throwable e e)))
          suite-done (fn [suite]
                       (attempt #(as-> (assoc (suite-testable hs suite)
                                              :kaocha.result/tests (get-in hs [:results suite] [])) t
                                   (run-chain :post-test (own hs suite :post-test) t p)
                                   (run-chain :post-test (top hs :post-test) t p))))
          suite-errors (mapv suite-done (sort-by str (:suites-tested hs)))
          run-error (when (:ran? hs)
                      (attempt #(run-chain :post-run (top hs :post-run) (result-plan hs))))]
      (vec (remove nil? (conj suite-errors run-error))))))

(defn post-summary!
  "Run the `post-summary` hooks `entries` in this process (the parent)
  with Kaocha-shaped `result`. Returns the Throwable that stopped them, or
  nil."
  [entries result]
  (try
    (chain :post-summary (mapv resolve-hook entries) entries result)
    nil
    (catch Throwable e e)))

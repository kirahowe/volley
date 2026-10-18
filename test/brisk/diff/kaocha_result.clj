(ns brisk.diff.kaocha-result
  "A Kaocha plugin for the differential end-to-end tests. After the summary
  it prints one line, `#brisk-diff {...}`, with every test var Kaocha
  planned: its status and assertion counts, in the shape of
  `brisk.run-state/result`'s `:vars`. Load it with
  `--plugin brisk.diff.kaocha-result/plugin`."
  (:require
   [kaocha.plugin :as plugin]
   [kaocha.result :as result]
   [kaocha.testable :as testable]))

(def marker
  "Starts the line that carries the summary."
  "#brisk-diff ")

(defn- var-status
  "`ns-pending?` is true when the enclosing namespace is pending: Kaocha
  then reports one pending test for the namespace and never runs its vars."
  [t ns-pending?]
  (let [pass (:kaocha.result/pass t 0)
        fail (:kaocha.result/fail t 0)
        error (:kaocha.result/error t 0)
        pending? (or ns-pending? (pos? (:kaocha.result/pending t 0)))]
    (cond-> {:pass pass :fail fail :error error
             :status (cond
                       pending? :skipped
                       (::testable/skip t) :skipped
                       (not (:kaocha.result/count t)) :not-run
                       (pos? error) :errored
                       (pos? fail) :failed
                       :else :passed)}
      pending? (assoc :reason :pending))))

(defn- children [t] (or (:kaocha.result/tests t) (:kaocha.test-plan/tests t)))

(defn- var-entries [t ns-pending?]
  (if (= :kaocha.type/var (::testable/type t))
    [[(symbol (::testable/id t)) (var-status t ns-pending?)]]
    (let [pending? (or ns-pending?
                       (and (= :kaocha.type/ns (::testable/type t))
                            (pos? (:kaocha.result/pending t 0))))]
      (mapcat #(var-entries % pending?) (children t)))))

(defn summarize
  "Per-var results of a Kaocha result map, keyed by qualified symbol. Vars
  Kaocha never reached (after fail-fast) have status `:not-run`."
  [result]
  {:vars (into (sorted-map) (mapcat #(var-entries % false)) (:kaocha.result/tests result))
   :totals (let [t (result/totals (:kaocha.result/tests result))]
             {:tests (::result/count t) :pending (::result/pending t)})
   :load-errors (into []
                      (comp (filter ::testable/load-error)
                            (map ::testable/id))
                      (testable/test-seq-with-skipped result))})

(defn- post-summary
  "Print the summary line, return the result unchanged."
  [result]
  (println (str marker (binding [*print-namespace-maps* false *print-length* nil *print-level* nil] (pr-str (summarize result)))))
  result)

;; What `kaocha.plugin/defplugin` expands to, written out so clj-kondo can
;; follow it.
(def plugin-hooks
  "The plugin's hooks: print the summary after Kaocha's."
  {:kaocha.plugin/id ::plugin
   :kaocha.plugin/description "Print per-var results for brisk's differential tests"
   :kaocha.hooks/post-summary post-summary})

(defmethod plugin/-register ::plugin [_ plugins]
  (conj plugins plugin-hooks))

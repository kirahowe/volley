(ns volley.engine-perf-test
  "The append-only reporters must stay linear in the number of
  namespaces: each `:ns/end` renders the namespace it names, not every
  namespace the run has seen."
  (:require
   [volley.engine :as engine]
   [volley.render :as render]
   [clojure.test :refer [deftest is]]))

(defn- respond
  "The events a worker sends for one `:run` message: one passing var."
  [worker {:keys [ns run-id]} at]
  [{:event :ns/start :ns ns :run-id run-id :worker worker :at at}
   {:event :var/start :ns ns :var 'one :file "t.clj" :line 1 :worker worker :at at}
   {:event :var/end :ns ns :var 'one :pass 1 :fail 0 :error 0 :duration-ms 1 :worker worker :at at}
   {:event :ns/end :ns ns :run-id run-id :duration-ms 1 :worker worker :at at}])

(defn- run-synthetic
  "Drive the engine through a run of `n` one-var namespaces with
  `reporter`, workers answering at once."
  [n reporter]
  (let [plan {:suites [{:id :unit :namespaces (mapv (fn [i] {:ns (symbol (str "n" i "-test"))})
                                                     (range n))}]}
        {:keys [state]} (engine/init plan {:workers 4 :reporter reporter :started-at 0})]
    (loop [state state input {:event :tick :at 0} pending [] at 0]
      (let [{:keys [state effects]} (engine/step state input)
            pending (into pending
                          (mapcat (fn [{:keys [effect worker msg]}]
                                    (case effect
                                      :spawn [{:event :worker/ready :worker worker :at at}]
                                      :send (when (= :run (:op msg)) (respond worker msg at))
                                      (:stop :kill) [{:event :worker/exit :worker worker :code 0 :at at}]
                                      nil)))
                          effects)]
        (cond
          (engine/done? state) nil
          (seq pending) (recur state (first pending) (subvec pending 1) (inc at))
          :else (recur state {:event :tick :at (+ at 100)} pending (+ at 100)))))))

(defn- visits
  "How many namespace records the renderer looks at over a run of `n`
  namespaces. Counting work rather than timing it keeps the test
  deterministic, so load from the rest of the suite cannot sway it."
  [n reporter]
  (let [seen (volatile! 0)
        candidates @#'render/candidates]
    (with-redefs [render/candidates (fn [state opts]
                                      (let [recs (candidates state opts)]
                                        (vswap! seen + (count recs))
                                        recs))]
      (run-synthetic n reporter))
    @seen))

(defn- growth
  "How many times more records a run of 4n namespaces visits than one of
  n: about 4 for a linear engine, about 16 for one that renders every
  namespace on each `:ns/end`."
  [n reporter]
  (double (/ (visits (* 4 n) reporter) (visits n reporter))))

(deftest ci-output-is-linear-in-namespaces
  (let [g (growth 50 :ci)]
    (is (< g 8) (str "growth ratio: " g))))

(deftest documentation-output-is-linear-in-namespaces
  (let [g (growth 50 :documentation)]
    (is (< g 8) (str "growth ratio: " g))))

(ns volley.engine-perf-test
  "The append-only reporters must stay linear in the number of
  namespaces: each `:ns/end` renders the namespace it names, not every
  namespace the run has seen."
  (:require
   [volley.engine :as engine]
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
  `reporter`, workers answering at once. Returns the number of printed
  lines."
  [n reporter]
  (let [plan {:suites [{:id :unit :namespaces (mapv (fn [i] {:ns (symbol (str "n" i "-test"))})
                                                     (range n))}]}
        {:keys [state]} (engine/init plan {:workers 4 :reporter reporter :started-at 0})]
    (loop [state state input {:event :tick :at 0} pending [] printed 0 at 0]
      (let [{:keys [state effects]} (engine/step state input)
            printed (+ printed (count (mapcat :lines (filter #(= :print (:effect %)) effects))))
            pending (into pending
                          (mapcat (fn [{:keys [effect worker msg]}]
                                    (case effect
                                      :spawn [{:event :worker/ready :worker worker :at at}]
                                      :send (when (= :run (:op msg)) (respond worker msg at))
                                      (:stop :kill) [{:event :worker/exit :worker worker :code 0 :at at}]
                                      nil)))
                          effects)]
        (cond
          (engine/done? state) printed
          (seq pending) (recur state (first pending) (subvec pending 1) printed (inc at))
          :else (recur state {:event :tick :at (+ at 100)} pending printed (+ at 100)))))))

(defn- time-ms [f]
  (let [t0 (System/nanoTime)]
    (f)
    (/ (- (System/nanoTime) t0) 1e6)))

(defn- growth
  "How much longer a run of 4n namespaces takes than one of n, best of
  three each."
  [n reporter]
  (run-synthetic 50 reporter)
  (let [best (fn [k] (apply min (repeatedly 3 #(time-ms (fn [] (run-synthetic k reporter))))))]
    (/ (best (* 4 n)) (best n))))

(deftest ci-output-is-linear-in-namespaces
  ;; Linear is 4x; quadratic would be 16x. The bound leaves room for noise.
  (is (< (growth 200 :ci) 8)))

(deftest documentation-output-is-linear-in-namespaces
  (is (< (growth 200 :documentation) 8)))

(ns volley.engine-stop-test
  "How the engine stops workers it no longer needs: ask first, kill after a
  grace period, so their shutdown hooks get a chance to run."
  (:require
   [volley.engine :as engine]
   [volley.gen.engine :as ge]
   [volley.run-state :as rs]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.properties :as prop]))

(def ^:private runs 150)
(def ^:private max-steps 4000)

(defn- feed
  "Step `state` through `inputs`; `[state effects]` with every effect."
  [state inputs]
  (reduce (fn [[s effs] input]
            (let [{:keys [state effects]} (engine/step s input)]
              [state (into effs effects)]))
          [state []]
          inputs))

(defn- of [kind effects] (set (keep #(when (= kind (:effect %)) (:worker %)) effects)))

(defn- assigned
  "`{ns worker}` from the run messages among `effects`."
  [effects]
  (into {} (keep (fn [{:keys [effect worker msg]}]
                   (when (and (= :send effect) (= :run (:op msg))) [(:ns msg) worker])))
        effects))

(defn- fail-fast-run
  "Two workers, `a-test` and `b-test` running, `a-test` then fails at 100."
  [opts]
  (let [plan {:suites [{:id :unit :load-errors []
                        :namespaces [{:ns 'a-test :select {}} {:ns 'b-test :select {}}]}]}
        {s :state} (engine/init plan (merge {:workers 2 :fail-fast? true :reporter :ci
                                             :width 120 :color? false :started-at 0}
                                            opts))
        {s :state e0 :effects} (engine/start s)
        [s e1] (feed s (map (fn [w] {:event :worker/ready :worker w :at 1}) (of :spawn e0)))
        {a 'a-test b 'b-test} (assigned (concat e0 e1))
        [s e2] (feed s [{:event :ns/start :worker a :ns 'a-test :at 2}
                        {:event :ns/start :worker b :ns 'b-test :at 2}
                        {:event :var/start :worker a :ns 'a-test :var 't :at 3}
                        {:event :assertion :worker a :ns 'a-test :var 't :type :fail :message "no" :at 100}])]
    {:state s :effects e2 :failing a :other b}))

(deftest fail-fast-asks-the-other-workers-to-stop-before-killing-them
  (let [{:keys [state effects other]} (fail-fast-run {})]
    (is (contains? (of :stop effects) other) "the interrupted worker is asked to shut down")
    (is (not (contains? (of :kill effects) other)) "and not killed at once")
    (testing "it is killed once the grace period is over"
      (let [[s e] (feed state [{:event :tick :at 1099}])]
        (is (empty? (of :kill e)))
        (let [[_ e] (feed s [{:event :tick :at 1100}])]
          (is (= #{other} (of :kill e))))))
    (testing "a worker that exits in time is not killed"
      (let [[_ e] (feed state [{:event :worker/exit :worker other :code 0 :at 500}
                               {:event :tick :at 1100}
                               {:event :tick :at 2000}])]
        (is (not (contains? (of :kill e) other)))))))

(deftest the-stop-grace-period-is-an-option
  (let [{:keys [state other]} (fail-fast-run {:stop-grace-ms 50})
        [_ e] (feed state [{:event :tick :at 150}])]
    (is (= #{other} (of :kill e)))))

(deftest a-shutdown-hook-error-of-an-interrupted-worker-counts
  (let [{:keys [state other]} (fail-fast-run {})
        [s _] (feed state [{:event :var/end :worker other :ns 'b-test :var 'u :pass 1 :at 200}
                           {:event :worker/error :worker other :phase :shutdown :at 300
                            :error {:class "E" :message "post-run boom" :trace []}}])]
    (is (nil? (get-in s [:run :vars 'b-test/u])) "its test events are still dropped")
    (is (= ["post-run boom"] (map (comp :message :error) (get-in s [:run :worker-errors other]))))
    (is (> (rs/exit-code (:run s)) (rs/exit-code (:run state))) "and counts as a failure")))

(defspec a-worker-asked-to-stop-gets-the-grace-period-before-a-kill runs
  (prop/for-all [w ge/gen-world]
    (let [{:keys [world]} (ge/simulate (assoc-in w [:opts :fail-fast?] true) max-steps)
          stopped (reduce (fn [m [effect worker at]]
                            (cond-> m (and (= :stop effect) (not (contains? m worker))) (assoc worker at)))
                          {} (:log world))]
      (every? (fn [[effect worker at]]
                (or (not= :kill effect)
                    (not (contains? stopped worker))
                    (>= (- at (stopped worker)) 1000)))
              (:log world)))))

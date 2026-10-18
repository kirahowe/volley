(ns brisk.gen.engine
  "Generators and a simulated outside world for `brisk.engine`: workers that
  start or fail to start, run namespaces to a chosen fate, crash, ignore
  shutdowns, and deliver their events in an arbitrary interleaving."
  (:require
   [brisk.engine :as engine]
   [clojure.test.check.generators :as gen]))

(def gen-fate
  (gen/frequency [[6 (gen/fmap (fn [n] [:pass n]) (gen/choose 0 3))]
                  [2 (gen/return [:fail])]
                  [1 (gen/return [:error])]
                  [1 (gen/return [:load-error])]
                  [1 (gen/return [:crash])]]))

(defn- ns-sym [i] (symbol (str "sim.ns" i "-test")))

(def gen-world
  "A plan plus everything the outside world will do, sometimes with worker
  ids that start above 1 and a stale worker of an earlier run whose events
  arrive mixed in with this run's."
  (gen/let [n (gen/choose 0 8)
            fates (gen/vector gen-fate n)
            serial (gen/vector gen/boolean n)
            file-errors (gen/choose 0 2)
            workers (gen/choose 1 4)
            fail-fast? (gen/frequency [[3 (gen/return false)] [1 (gen/return true)]])
            isolate? gen/boolean
            spawn-fails (gen/vector (gen/frequency [[8 (gen/return false)] [1 (gen/return true)]]) 0 30)
            all-spawns-fail? (gen/frequency [[12 (gen/return false)] [1 (gen/return true)]])
            hung? (gen/frequency [[8 (gen/return false)] [1 (gen/return true)]])
            choices (gen/vector (gen/choose 0 1000) 0 600)
            reporter (gen/elements [:ci :documentation :live])
            first-id (gen/frequency [[2 (gen/return 1)] [1 (gen/choose 2 60)]])
            stale? gen/boolean]
    (let [entries (mapv (fn [i] {:ns (ns-sym i) :file (str "test/sim/ns" i "_test.clj")
                                 :serial? (nth serial i) :select {:passes [] :ns-meta {}}})
                        (range n))]
      {:plan {:suites [{:id :unit :namespaces entries
                        :load-errors (mapv (fn [i] {:file (str "test/bad" i "_test.clj") :error "EOF"})
                                           (range file-errors))}]
              :warnings [] :errors []}
       :fates (zipmap (map :ns entries) fates)
       :opts {:workers workers :fail-fast? fail-fast? :isolate? isolate? :capture-output? true
              :reporter reporter :width 120 :color? false :started-at 0 :durations {}
              :first-worker-id first-id}
       :spawn-fails (if all-spawns-fail? (vec (repeat 100 true)) spawn-fails)
       :hung? hung?
       :stale? (and stale? (> first-id 1))
       :choices choices})))

(def stale-ns
  "The namespace a worker of an earlier run reports in `simulate` when the
  world is `:stale?`."
  'stale.earlier-run-test)

(defn- ns-events [ns-sym fate]
  (let [v 'a-test
        start [{:event :ns/start :ns ns-sym}]
        var-start {:event :var/start :ns ns-sym :var v :file "f.clj" :line 1}
        end [{:event :ns/end :ns ns-sym :duration-ms 5}]]
    (case (first fate)
      :pass (concat start
                    (mapcat (fn [j] [(assoc var-start :var (symbol (str "t" j)))
                                     {:event :var/end :ns ns-sym :var (symbol (str "t" j)) :pass 1 :duration-ms 1}])
                            (range (second fate)))
                    end)
      :fail (concat start [var-start
                           {:event :assertion :ns ns-sym :var v :type :fail :message "no"}
                           {:event :var/end :ns ns-sym :var v :pass 0 :fail 1 :duration-ms 1}]
                    end)
      :error (concat start [var-start
                            {:event :assertion :ns ns-sym :var v :type :error
                             :error {:class "E" :message "boom" :trace []}}
                            {:event :var/end :ns ns-sym :var v :pass 0 :error 1 :duration-ms 1}]
                     end)
      :load-error (concat start [{:event :ns/error :ns ns-sym :error {:class "L" :message "x" :trace []}}] end)
      :crash (concat start [var-start {:event :output :text "dying" :stream :stderr}]))))

(defn- enqueue [world id events]
  (update-in world [:workers id :queue] (fnil into []) events))

(defn- interpret
  "Apply one engine effect to the simulated world."
  [world {:keys [effect worker msg] :as e}]
  (case effect
    :spawn (let [fail? (get (:spawn-fails world) (:spawn-count world 0) false)
                 world (-> world
                           (update :spawn-count (fnil inc 0))
                           (update :spawned conj worker))]
             (-> world
                 (assoc-in [:workers worker] {:queue [] :exiting? false})
                 (enqueue worker (if fail?
                                   [{:event :worker/exit :code 1}]
                                   [{:event :worker/ready}]))
                 (cond-> fail? (assoc-in [:workers worker :exiting?] true))))
    :send (let [w (get-in world [:workers worker])]
            (cond
              (or (nil? w) (:exiting? w)) world
              :else (let [fate (get (:fates world) (:ns msg))
                          evs (ns-events (:ns msg) fate)]
                      (if (= :crash (first fate))
                        (-> world
                            (enqueue worker (concat evs [{:event :worker/exit :code 1}]))
                            (assoc-in [:workers worker :exiting?] true))
                        (enqueue world worker evs)))))
    :stop (let [w (get-in world [:workers worker])]
            (if (or (nil? w) (:exiting? w) (:hung? world))
              world
              (-> world
                  (enqueue worker [{:event :worker/exit :code 0}])
                  (assoc-in [:workers worker :exiting?] true))))
    :kill (let [w (get-in world [:workers worker])
                world (update world :kills (fnil inc 0))]
            (if (or (nil? w) (:exiting? w))
              world
              (-> world
                  (enqueue worker [{:event :worker/exit :code 137}])
                  (assoc-in [:workers worker :exiting?] true))))
    (:print :redraw) (update world :output conj e)
    :save-timings (assoc world :timings (:timings e))
    :exit (assoc world :exit (:code e))))

(defn- next-input
  "Pick the next input: an event from some worker's queue, or a tick."
  [world choice]
  (let [ready (vec (sort (keep (fn [[id w]] (when (seq (:queue w)) id)) (:workers world))))
        options (count ready)
        pick (mod choice (inc options))]
    (if (= pick options)
      [(update world :clock + 80) {:event :tick :at (+ (:clock world) 80)}]
      (let [id (nth ready pick)
            ev (first (get-in world [:workers id :queue]))
            world (-> world
                      (update-in [:workers id :queue] subvec 1)
                      (update :clock inc))]
        [world (assoc ev :worker id :at (:clock world))]))))

(defn simulate
  "Run the engine against a generated world until it exits or `max-steps`
  inputs have been fed. Returns the world (with `:output` effects, `:exit`)
  and the final engine state."
  [{:keys [plan opts fates spawn-fails hung? stale? choices]} max-steps]
  (let [{:keys [state effects]} (engine/init plan opts)
        stale-worker (dec (:first-worker-id opts 1))
        workers (if stale?
                  {stale-worker {:exiting? true
                                 :queue (vec (concat (ns-events stale-ns [:fail])
                                                     [{:event :worker/exit :code 137}]))}}
                  {})
        world (reduce interpret {:workers workers :clock 0 :fates fates :spawn-fails spawn-fails
                                 :hung? hung? :output [] :spawned []}
                      effects)
        {state :state effects :effects} (engine/start state)
        world (reduce interpret world effects)]
    (loop [state state world world cs (seq choices) n 0]
      (if (or (contains? world :exit) (>= n max-steps))
        {:world world :state state :steps n}
        (let [[world input] (next-input world (or (first cs) 0))
              {state :state effects :effects} (engine/step state input)]
          (recur state (reduce interpret world effects) (next cs) (inc n)))))))

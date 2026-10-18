(ns brisk.watch-test
  (:require
   [brisk.discover :as discover]
   [brisk.filters :as filters]
   [brisk.gen.watch :as gw]
   [brisk.select :as select]
   [brisk.watch :as watch]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.properties :as prop]))

;; ---------------------------------------------------------------------------
;; Mtime diffing

(defspec changes-is-the-difference-between-snapshots 300
  (prop/for-all [prev gw/snapshot snap gw/snapshot]
    (let [{:keys [added modified deleted]} (watch/changes prev snap)
          pk (set (keys prev)) sk (set (keys snap))]
      (and (= (set added) (set/difference sk pk))
           (= (set deleted) (set/difference pk sk))
           (= (set modified) (set (filter #(not= (prev %) (snap %)) (set/intersection pk sk))))
           (= added (vec (sort added)))
           (empty? (set/intersection (set added) (set modified)))
           (empty? (set/intersection (set modified) (set deleted)))))))

(defspec identical-snapshots-have-no-changes 100
  (prop/for-all [snap gw/snapshot]
    (empty? (watch/changed-paths (watch/changes snap snap)))))

(defspec changes-reverse-when-snapshots-swap 200
  (prop/for-all [a gw/snapshot b gw/snapshot]
    (let [ab (watch/changes a b) ba (watch/changes b a)]
      (and (= (:added ab) (:deleted ba))
           (= (:deleted ab) (:added ba))
           (= (:modified ab) (:modified ba))))))

(defspec applying-changes-brings-the-old-snapshot-up-to-date 200
  (prop/for-all [a gw/snapshot b gw/snapshot]
    (let [{:keys [added modified deleted]} (watch/changes a b)
          rebuilt (as-> a m
                    (apply dissoc m deleted)
                    (into m (map (fn [p] [p (b p)])) (concat added modified)))]
      (= rebuilt b))))

(deftest ignores-editor-droppings
  (is (watch/watched-file? "src/a/b.clj"))
  (is (watch/watched-file? "../other/src/a.clj"))
  (is (watch/watched-file? "tests.edn"))
  (is (not-any? watch/watched-file?
                ["src/.#a.clj" "src/a.clj~" "src/#a.clj#" "src/.a.clj.swp" ".git/HEAD" "src/a.clj.tmp"
                 ".brisk/timings.edn"])))

;; ---------------------------------------------------------------------------
;; The state machine

(defn- run-inputs
  "Feed `inputs`, collecting every effect. Returns `[state effects]`."
  [state inputs]
  (reduce (fn [[st effs] input]
            (let [{s :state e :effects} (watch/step st input)]
              [s (into effs e)]))
          [state []]
          inputs))

(def ^:private fresh (watch/init {:keys? true :config-file "tests.edn"}))

(defn- keys-of [s] (map (fn [c] {:event :key :char c}) s))

(defn- starts [effects] (filter #(= :start-run (:effect %)) effects))

(defn- timeline-inputs
  "The timeline's events merged with a tick every 80 ms."
  [events]
  (let [end (+ 2000 (apply max (map :at events)))
        ticks (map (fn [t] {:event :tick :at t}) (range 0 end 80))
        evs (map #(assoc % :event :files-changed) events)]
    (sort-by :at (concat evs ticks))))

(defn- drive-timeline
  "Every started run, each finishing at once."
  [events]
  (second
   (reduce (fn [[st runs] input]
             (let [{s :state effs :effects} (watch/step st input)
                   started (starts effs)
                   s (if (seq started)
                       (:state (watch/step s {:event :run/finished :empty? true}))
                       s)]
               [s (into runs started)]))
           [fresh []]
           (timeline-inputs events))))

(defspec debounce-fires-once-per-burst-and-drops-nothing 200
  (prop/for-all [events gw/timeline]
    (let [runs (drive-timeline events)
          bursts (partition-by :burst events)]
      (and (= (count bursts) (count runs))
           (every? (fn [[burst run]]
                     (= (set (mapcat :affected burst)) (set (get-in run [:selection :namespaces]))))
                   (map vector bursts runs))))))

(deftest a-change-fires-only-after-quiet
  (let [{s :state} (watch/step fresh {:event :files-changed :paths ["src/a.clj"] :affected '#{a-test} :at 1000})
        too-soon (watch/step s {:event :tick :at (+ 1000 (dec watch/debounce-ms))})
        due (watch/step s {:event :tick :at (+ 1000 watch/debounce-ms)})]
    (is (empty? (:effects too-soon)))
    (is (= [{:all? false :failed? false :namespaces '#{a-test} :reload-config? false
             :ns-pattern nil :test-pattern nil}]
           (map :selection (starts (:effects due)))))))

(deftest a-config-change-reruns-everything-with-a-reload
  (let [{s :state} (watch/step fresh {:event :files-changed :paths ["tests.edn"] :affected '#{} :at 0})
        {:keys [effects]} (watch/step s {:event :tick :at 1000})]
    (is (= [true true] ((juxt :all? :reload-config?) (:selection (first (starts effects))))))))

(deftest a-change-while-running-cancels-and-keeps-the-running-selection
  (let [[s _] (run-inputs fresh [{:event :files-changed :paths ["src/a.clj"] :affected '#{a-test} :at 0}
                                 {:event :tick :at 500}
                                 {:event :files-changed :paths ["src/b.clj"] :affected '#{b-test} :at 600}])
        {:keys [effects]} (watch/step s {:event :tick :at 1000})]
    (is (= [:print :cancel-run :start-run] (map :effect effects)))
    (is (= '#{a-test b-test} (:namespaces (:selection (last effects)))))))

(deftest a-change-nothing-depends-on-does-not-disturb-a-run
  (let [[s _] (run-inputs fresh [{:event :start}
                                 {:event :files-changed :paths ["src/z.clj"] :affected #{} :at 0}])]
    (is (empty? (:effects (watch/step s {:event :tick :at 1000}))))))

(deftest prompt-applies-a-namespace-filter-and-reruns
  (let [[s effs] (run-inputs fresh (concat [{:event :start} {:event :run/finished :empty? true}]
                                           (keys-of "p")
                                           (keys-of "ab\u007fc")
                                           (keys-of "\r")))]
    (is (= {:ns "ac" :test nil} (:filters s)))
    (is (= :running (:mode s)))
    (is (= "ac" (-> (starts effs) last :selection :ns-pattern)))
    (is (some #(= {:effect :prompt :label "filter namespaces (regex)" :buffer "ab"} %) effs))))

(deftest invalid-regex-keeps-the-prompt-open
  (let [[s effs] (run-inputs fresh (concat (keys-of "t") (keys-of "(") (keys-of "\r")))]
    (is (= :prompting (:mode s)))
    (is (some #(str/starts-with? (str (first (:lines %))) "Invalid regex") effs))))

(deftest empty-filter-clears
  (let [[s _] (run-inputs fresh (concat [{:event :start} {:event :run/finished :empty? true}]
                                        (keys-of "p") (keys-of "a\r")
                                        [{:event :run/finished :empty? true}]
                                        (keys-of "p") (keys-of "\r")))]
    (is (nil? (get-in s [:filters :ns])))))

(deftest f-reruns-failures-only-when-there-are-some
  (let [fin (fn [failed] {:event :run/finished :outcome {:ran #{'a-test} :failed failed}})
        [_ none] (run-inputs fresh [{:event :start} (fin {}) {:event :key :char \f}])
        [s some*] (run-inputs fresh [{:event :start} (fin {'a-test #{'x}}) {:event :key :char \f}])]
    (is (empty? (starts (rest none))))
    (is (= [true] (map (comp :failed? :selection) (starts (drop-while #(not= :print (:effect %)) some*)))))
    (is (= :running (:mode s)))))

(deftest status-line-reports-pass-and-fail
  (let [lines (fn [failed]
                (-> (run-inputs fresh [{:event :start}
                                       {:event :run/finished :outcome {:ran #{'a-test} :failed failed}}])
                    second last :lines))]
    (is (= ["PASS  Waiting for file changes...  press h to show help, q to quit"] (lines {})))
    (is (= ["FAIL  2 failures  Waiting for file changes...  press h to show help, q to quit"]
           (lines {'a-test #{'x 'y}})))))

(deftest failures-of-namespaces-not-rerun-are-kept
  (let [a {:ran '#{a-test b-test} :failed {'a-test #{'x} 'b-test #{}}}
        b {:ran '#{a-test} :failed {}}
        merged (watch/merge-outcome (watch/merge-outcome nil a) b)]
    (is (= {'b-test #{}} (:failed merged)))
    (is (false? (:ok? merged)))))

(deftest keys-are-not-advertised-without-a-terminal
  (let [lines (-> (run-inputs (watch/init {:keys? false}) [{:event :start} {:event :run/finished :empty? true}])
                  second last :lines)]
    (is (not (str/includes? (first lines) "press h")))))

(defspec any-input-sequence-leaves-a-usable-state 300
  (prop/for-all [inputs gw/inputs]
    (let [[s _] (run-inputs fresh inputs)
          escaped (:state (watch/step s {:event :key :char \u001b}))
          quit (watch/step escaped {:event :key :char \q})]
      (and (contains? #{:idle :running :prompting :quit} (:mode s))
           (= (= :prompting (:mode s)) (some? (:prompt s)))
           ;; Esc always leaves the prompt, q always quits.
           (not= :prompting (:mode escaped))
           (or (= :quit (:mode s))
               (and (= :quit (:mode (:state quit)))
                    (some #(= :quit (:effect %)) (:effects quit))))))))

(defspec quit-cancels-a-run-in-progress 100
  (prop/for-all [inputs gw/inputs]
    (let [[s _] (run-inputs fresh inputs)
          {:keys [effects]} (watch/step (assoc s :mode :running :prompt nil) {:event :key :char \q})]
      (or (= :quit (:mode s))
          (= [:cancel-run :quit] (map :effect effects))))))

;; ---------------------------------------------------------------------------
;; Selection

(defn- plan-nses [plan] (into #{} (comp (mapcat :namespaces) (map :ns)) (:suites plan)))

(defspec reruns-stay-within-the-configs-selection-and-the-filters 200
  (prop/for-all [{:keys [infos config selection failed]} gw/project]
    (let [full (plan-nses (select/plan config (vals infos) []))
          ran (plan-nses (watch/select-plan config infos [] selection failed))
          re (watch/compile-pattern (:ns-pattern selection))]
      (and (set/subset? ran full)
           (every? #(or (nil? re) (re-find re (str %))) ran)
           (or (:all? selection)
               (set/subset? ran (into (:namespaces selection) (when (:failed? selection) (keys failed)))))))))

(defspec unfiltered-full-rerun-is-the-configs-plan 100
  (prop/for-all [{:keys [infos config]} gw/project]
    (= (select/plan config (vals infos) [])
       (watch/select-plan config infos [] {:all? true} {}))))

(defspec affected-only-reruns-are-a-subset-of-the-full-selection 200
  (prop/for-all [{:keys [infos config selection]} gw/project]
    (let [sel (assoc selection :all? false :failed? false)
          full (plan-nses (watch/select-plan config infos [] (assoc sel :all? true) {}))]
      (set/subset? (plan-nses (watch/select-plan config infos [] sel {})) full))))

(defspec test-patterns-reach-the-worker-and-prune-static-misses 200
  (prop/for-all [{:keys [infos config selection]} gw/project]
    (let [sel (assoc selection :all? true)
          plan (watch/select-plan config infos [] sel {})
          by-ns (into {} (map (juxt :ns identity)) (vals infos))
          re (watch/compile-pattern (:test-pattern sel))]
      (every? (fn [{:keys [ns select]}]
                (and (= (:test-pattern sel) (when re (:var-pattern select)))
                     (or (nil? re)
                         (empty? (:vars (by-ns ns)))
                         (some #(re-find re (name %)) (keys (:vars (by-ns ns)))))))
              (mapcat :namespaces (:suites plan))))))

(deftest failed-reruns-focus-the-failed-vars
  (let [infos {"test/a_test.clj" {:file "test/a_test.clj" :ns 'a-test :vars {'x {} 'y {}}}
               "test/b_test.clj" {:file "test/b_test.clj" :ns 'b-test :vars {'z {}}}}
        config {:tests [{:id :unit :type :kaocha.type/clojure.test :test-paths ["test"]
                         :ns-patterns ["-test$"] :focus [] :skip [] :focus-meta [] :skip-meta []}]
                :filters {:focus [] :skip [] :focus-meta [] :skip-meta []}}
        plan (watch/select-plan config infos [] {:failed? true} {'a-test #{'y}})
        spec (-> plan :suites first :namespaces first :select)]
    (is (= ['a-test] (map :ns (-> plan :suites first :namespaces))))
    (is (= {:run? true :reason :selected} (filters/decide spec 'a-test 'y {})))
    (is (= :not-focused (:reason (filters/decide spec 'a-test 'x {}))))))

(deftest var-pattern-skips-non-matching-vars
  (let [spec {:passes [] :ns-meta {} :var-pattern "^add"}]
    (is (:run? (filters/decide spec 'a-test 'adds {})))
    (is (= :not-focused (:reason (filters/decide spec 'a-test 'divides {}))))))

;; ---------------------------------------------------------------------------
;; Affected namespaces

(def ^:private suites
  [{:id :unit :type :kaocha.type/clojure.test :test-paths ["test"] :ns-patterns ["-test$"]}])

(defn- info-of [path text] [path (discover/ns-info path text)])

(deftest affected-by-follows-adds-edits-and-deletes
  (let [old (into {} [(info-of "src/a.clj" "(ns a)")
                      (info-of "test/a_test.clj" "(ns a-test (:require a))")
                      (info-of "test/b_test.clj" "(ns b-test)")])
        ch {:added ["test/c_test.clj"] :modified ["src/a.clj"] :deleted []}
        new (watch/update-infos old ch {"test/c_test.clj" "(ns c-test (:require a))" "src/a.clj" "(ns a)"})]
    (is (= '#{a-test c-test} (watch/affected-by suites old new ch)))
    (let [del {:added [] :modified [] :deleted ["src/a.clj"]}
          new2 (watch/update-infos new del {})]
      (is (= '#{a-test c-test} (watch/affected-by suites new new2 del)))
      (is (not (contains? new2 "src/a.clj"))))
    (testing "a file the graph cannot place affects every test namespace"
      (is (= '#{a-test b-test}
             (watch/affected-by suites old old {:added [] :modified ["resources/x.edn"] :deleted []}))))))

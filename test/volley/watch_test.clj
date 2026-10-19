(ns volley.watch-test
  (:require
   [volley.config :as config]
   [volley.discover :as discover]
   [volley.filters :as filters]
   [volley.gen.watch :as gw]
   [volley.run-state :as rs]
   [volley.select :as select]
   [volley.watch :as watch]
   [clojure.java.io :as io]
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
                 ".volley/timings.edn"])))

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
             :reload-deps? false :ns-pattern nil :test-pattern nil}]
           (map :selection (starts (:effects due)))))))

(deftest a-config-change-reruns-everything-with-a-reload
  (let [{s :state} (watch/step fresh {:event :files-changed :paths ["tests.edn"] :affected '#{} :at 0})
        {:keys [effects]} (watch/step s {:event :tick :at 1000})]
    (is (= [true true false] ((juxt :all? :reload-config? :reload-deps?) (:selection (first (starts effects))))))))

(deftest a-deps-change-reruns-everything-with-a-fresh-classpath
  (doseq [file ["deps.edn" "bb.edn"]]
    (let [{s :state} (watch/step fresh {:event :files-changed :paths [file] :affected '#{} :at 0})
          {:keys [effects]} (watch/step s {:event :tick :at 1000})]
      (is (= [true true true] ((juxt :all? :reload-config? :reload-deps?) (:selection (first (starts effects)))))
          file))))

(deftest a-deps-change-during-a-run-survives-the-merge
  (let [[s _] (run-inputs fresh [{:event :start}
                                 {:event :files-changed :paths ["deps.edn"] :affected #{} :at 0}
                                 {:event :tick :at 500}
                                 {:event :files-changed :paths ["src/a.clj"] :affected '#{a-test} :at 600}])
        {:keys [effects]} (watch/step s {:event :tick :at 1000})]
    (is (:reload-deps? (:selection (last (starts effects)))))))

(deftest deps-files-are-the-project-files
  (is (every? watch/deps-file? ["deps.edn" "bb.edn"]))
  (is (not-any? watch/deps-file? ["src/deps.edn" "tests.edn" "deps.edn~"])))

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

(deftest failures-of-unselected-namespaces-are-kept
  (let [skipped {:phase :skipped :reason :not-focused}
        run (assoc (rs/init {:namespaces '[a-test b-test]})
                   :namespaces {'a-test {:ns 'a-test :status :done :vars {'x skipped}}
                                'b-test {:ns 'b-test :status :done :vars {'only-in-b {:phase :done}}
                                         :pass 1}})
        prev (watch/merge-outcome nil {:ran '#{a-test b-test} :failed {'a-test #{'x}}})
        merged (watch/merge-outcome prev (watch/outcome run))]
    (is (= '#{b-test} (:ran (watch/outcome run))))
    (is (= {'a-test #{'x}} (:failed merged)) "a t filter that skips a-test keeps its failure")))

(deftest a-namespace-left-with-no-vars-clears-its-failures
  (let [run (assoc (rs/init {:namespaces '[a-test]})
                   :namespaces {'a-test {:ns 'a-test :status :done :vars {}}})
        prev (watch/merge-outcome nil {:ran '#{a-test} :failed {'a-test #{'x}}})
        merged (watch/merge-outcome prev (watch/outcome run))]
    (is (= '#{a-test} (:ran (watch/outcome run))))
    (is (= {} (:failed merged)))
    (is (:ok? merged))))

(defspec failures-of-vars-a-filter-skipped-are-kept 300
  (prop/for-all [{:keys [prev run]} gw/outcome-pair]
    (let [{:keys [ran failed skipped]} run
          old (:failed prev)
          merged (:failed (watch/merge-outcome prev run))
          old-failure? (fn [ns v] (contains? (get old ns) v))
          explained? (fn [ns v]
                       (or (contains? (get failed ns) v)
                           (and (contains? (get skipped ns) v) (old-failure? ns v))
                           (and (not (ran ns)) (old-failure? ns v))))]
      (and
       ;; Every merged failure failed this run, is an old failure of a
       ;; skipped var, or belongs to a namespace outside :ran.
       (every? (fn [[ns vars]]
                 (if (= #{} vars)
                   (or (= #{} (get failed ns))
                       (and (not (ran ns)) (= #{} (get old ns))))
                   (every? #(explained? ns %) vars)))
               merged)
       ;; Namespaces outside :ran carry over unchanged.
       (every? (fn [[ns vars]] (or (ran ns) (= vars (get merged ns)))) old)
       ;; Old failures of skipped vars survive, unless the whole namespace
       ;; failed this run.
       (every? (fn [[ns vars]]
                 (let [now (get merged ns)]
                   (or (= #{} now)
                       (every? #(or (not (old-failure? ns %)) (contains? now %)) vars))))
               skipped)))))

(defn- run-of
  "A finished run of `a-test`: var `ok` passes, and the vars in `failing`
  fail. With `skipped`, the `t` filter skipped those vars instead."
  [failing skipped]
  (let [ev #(merge {:worker 0 :at 1000 :ns 'a-test} %)]
    (reduce rs/apply-event (rs/init {:namespaces '[a-test]})
            (concat [(ev {:event :ns/start})
                     (ev {:event :var/start :var 'ok})
                     (ev {:event :var/end :var 'ok :pass 1 :duration-ms 1})]
                    (mapcat (fn [v] [(ev {:event :var/start :var v})
                                     (ev {:event :assertion :var v :type :fail})
                                     (ev {:event :var/end :var v :pass 0 :duration-ms 1})])
                            failing)
                    (map (fn [v] (ev {:event :var/skipped :var v :reason :not-focused})) skipped)
                    [(ev {:event :ns/end :pass 1})]))))

(deftest a-test-filter-that-skips-a-failing-var-keeps-its-failure
  (let [fin (fn [run] {:event :run/finished :outcome (watch/outcome run) :known '#{a-test}})
        [s effs] (run-inputs fresh (concat [{:event :start} (fin (run-of '[bad] []))]
                                           (keys-of "t^ok$\r")
                                           [(fin (run-of [] '[bad]))]))]
    (is (= {'a-test #{'bad}} (get-in s [:last :failed])))
    (is (str/starts-with? (first (:lines (last effs))) "FAIL  1 failure"))
    (testing "running the var again replaces its failure"
      (let [[s _] (run-inputs s (concat (keys-of "c") [(fin (run-of [] []))]))]
        (is (= {} (get-in s [:last :failed])))))))

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

(defspec test-patterns-reach-the-worker-and-keep-every-namespace 200
  ;; Any namespace may hold a macro-defined test whose name matches, so the
  ;; pattern prunes nothing statically; the worker applies it to real vars.
  (prop/for-all [{:keys [infos config selection]} gw/project]
    (let [sel (assoc selection :all? true)
          plan (watch/select-plan config infos [] sel {})
          unfiltered (watch/select-plan config infos [] (dissoc sel :test-pattern) {})
          re (watch/compile-pattern (:test-pattern sel))]
      (and (= (plan-nses unfiltered) (plan-nses plan))
           (every? (fn [{:keys [select]}]
                     (= (:test-pattern sel) (when re (:var-pattern select))))
                   (mapcat :namespaces (:suites plan)))))))

(defn- fixture-infos
  "Discovered infos of the files of dev-resources fixture `project`, keyed by
  path relative to the project."
  [project]
  (let [root (io/file "dev-resources/projects" project)]
    (into {}
          (comp (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))
                (map (fn [^java.io.File f]
                       (let [rel (str (.relativize (.toPath root) (.toPath f)))]
                         [rel (discover/ns-info rel (slurp f))]))))
          (file-seq root))))

(deftest test-pattern-keeps-namespaces-with-macro-defined-tests
  (let [config (config/normalize (:config (config/read-config (slurp "dev-resources/projects/generated/tests.edn") {})) {})
        plan (watch/select-plan config (fixture-infos "generated") []
                                {:all? true :test-pattern "^generated$"} {})
        spec (->> plan :suites (mapcat :namespaces) (filter #(= 'g.gen-test (:ns %))) first :select)]
    (is (some? spec) "g.gen-test shows only `literal`, but its macro defines `generated`")
    (is (:run? (filters/decide spec 'g.gen-test 'generated {})))
    (is (not (:run? (filters/decide spec 'g.gen-test 'literal {}))))))

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

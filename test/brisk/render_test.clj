(ns brisk.render-test
  (:require
   [brisk.gen.report :as gr]
   [brisk.render :as render]
   [brisk.run-state :as rs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

(def ^:private runs 60)

;;; Helpers

(defn- cp-width [cp]
  (cond (or (<= 0x300 cp 0x36F) (<= 0x200B cp 0x200F) (= cp 0xFE0F)) 0
        (or (<= 0x1100 cp 0x115F) (<= 0x2E80 cp 0xA4CF) (<= 0xAC00 cp 0xD7A3)
            (<= 0x1F300 cp 0x1F6FF) (<= 0xFF00 cp 0xFF60)) 2
        :else 1))

(defn- visible-width [line]
  (->> (str/replace line #"\u001b\[[0-9;]*m" "")
       (map int)
       (map cp-width)
       (reduce +)))

(defn- all-lines [{:keys [emit frame]}] (concat emit frame))

(def ^:private width-gen (gen/one-of [(gen/choose 0 6) (gen/choose 7 140)]))

(def ^:private opts-gen
  (gen/let [width width-gen
            color? gen/boolean
            now (gen/choose 0 100000)]
    {:width width :color? color? :now now}))

(defn- renderings
  "Every public rendering of a generated partial state, as a seq of line seqs."
  [{:keys [plan state]} opts]
  (let [prev (rs/init plan)]
    [(all-lines (render/live-frame nil state opts))
     (all-lines (render/live-frame prev state opts))
     (render/ci-lines nil state opts)
     (str/split-lines (render/failure-report state opts))
     (str/split-lines (render/summary state opts))]))

;;; Properties

(defspec every-line-fits-the-width runs
  (prop/for-all [ps gr/partial-state
                 opts opts-gen]
    (every? #(<= (visible-width %) (:width opts))
            (apply concat (renderings ps opts)))))

(defspec no-escape-codes-without-color runs
  (prop/for-all [ps gr/partial-state
                 opts opts-gen]
    (let [opts (assoc opts :color? false)]
      (not-any? #(str/includes? % "\u001b") (apply concat (renderings ps opts))))))

(defspec color-output-contains-only-sgr-escapes runs
  (prop/for-all [ps gr/partial-state
                 opts opts-gen]
    (let [opts (assoc opts :color? true)]
      (every? #(not (re-find #"\u001b(?!\[[0-9;]*m)" %))
              (apply concat (renderings ps opts))))))

(defspec rendering-is-total runs
  (prop/for-all [ps gr/partial-state
                 opts opts-gen]
    (every? sequential? (renderings ps opts))))

(defspec every-failure-appears-in-the-report runs
  (prop/for-all [ps gr/partial-state]
    (let [state (:state ps)
          report (render/failure-report state {:width 200 :color? false})]
      (every? (fn [{:keys [kind ns var]}]
                (case kind
                  (:fail :error :ns-error) (str/includes? report (str ns (when (and var (not= :ns-error kind)) (str "/" var))))
                  :crash (str/includes? report "worker exited")))
              (rs/failures state)))))

(defspec no-failures-no-report runs
  (prop/for-all [ps gr/partial-state]
    (let [state (:state ps)]
      (= (empty? (rs/failures state))
         (str/blank? (render/failure-report state {:width 80 :color? false}))))))

(defn- transition-emits
  "Replays events, rendering after each one with the previous state."
  [plan events opts]
  (loop [prev nil state (rs/init plan) evs events emitted []]
    (let [frame (render/live-frame prev state opts)
          emitted (into emitted (:emit frame))]
      (if-let [[e & more] (seq evs)]
        (recur state (rs/apply-event state e) more emitted)
        emitted))))

(defspec each-finished-namespace-is-emitted-exactly-once runs
  (prop/for-all [r gr/run]
    (let [events (gr/events r 0)
          opts {:width 300 :color? false :now 5000}
          emitted (transition-emits (:plan r) (conj (vec events) {:event :noop}) opts)
          final (gr/fold (:plan r) events)
          done (filter #(not= :running (rs/ns-result %)) (vals (:namespaces final)))]
      (every? (fn [rec]
                (= 1 (count (filter #(re-find (re-pattern (str "^ [✓✗] " (:ns rec) " ")) %) emitted))))
              done))))

(defspec diff-of-a-value-with-itself-is-empty runs
  (prop/for-all [x gr/edn-value]
    (let [d (render/diff x x)]
      (and (= :same (:type d))
           (= {:missing 0 :extra 0 :changed 0} (render/diff-stats d))))))

(defspec diff-is-same-iff-equal runs
  (prop/for-all [x gr/edn-value
                 y gr/edn-value]
    (= (= x y) (= :same (:type (render/diff x y))))))

(def ^:private flat
  (gen/one-of [gr/scalar (gen/vector gr/scalar 0 6) (gen/set gr/scalar {:max-elements 5})]))

(def ^:private symmetric-value
  "Values whose diffs pair items without ambiguity: maps (matched by key),
  sets, and sequences of scalars. Nested sequences of containers can pair
  their items differently in each direction, which changes nested counts."
  (gen/one-of [flat
               (gen/map gr/scalar flat {:max-elements 5})
               (gen/map gr/scalar (gen/map gr/scalar flat {:max-elements 3}) {:max-elements 3})]))

(defspec diff-missing-and-extra-are-symmetric 300
  (prop/for-all [x symmetric-value
                 y symmetric-value]
    (let [a (render/diff-stats (render/diff x y))
          b (render/diff-stats (render/diff y x))]
      ;; A missing/extra pair at one position is reported as one change, and
      ;; which items get paired can depend on direction, so compare the
      ;; totals of items that exist on only one side or differ.
      (and (= (+ (:missing a) (:changed a)) (+ (:extra b) (:changed b)))
           (= (+ (:extra a) (:changed a)) (+ (:missing b) (:changed b)))))))

(defspec diff-rendering-fits-and-is-total runs
  (prop/for-all [x gr/edn-value
                 y gr/edn-value
                 color? gen/boolean]
    (every? string? (render/diff-lines (render/diff x y) {:color? color?}))))

;;; Examples

(def ^:private opts {:width 80 :color? false :now 10000})

(defn- ev [m] (merge {:worker 0 :at 1000} m))

(def ^:private events
  [(ev {:event :ns/start :ns 'my.app-test :at 1000})
   (ev {:event :var/start :ns 'my.app-test :var 'add-test :file "test/my/app_test.clj" :line 5 :at 1000})
   (ev {:event :var/end :ns 'my.app-test :var 'add-test :pass 2 :duration-ms 12 :at 1012})
   (ev {:event :var/start :ns 'my.app-test :var 'merge-test :file "test/my/app_test.clj" :line 12 :at 1012})
   (ev {:event :assertion :ns 'my.app-test :var 'merge-test :type :fail
        :message "merge broke" :contexts ["outer" "inner"]
        :expected {:str "(= {:a 1, :b {:c 2}} {:a 1, :b {:c 3}, :d 4})"
                   :edn (list '= {:a 1 :b {:c 2}} {:a 1 :b {:c 3} :d 4})}
        :actual {:str "(not (= ...))"
                 :edn (list 'not (list '= {:a 1 :b {:c 2}} {:a 1 :b {:c 3} :d 4}))}
        :file "test/my/app_test.clj" :line 14 :at 1020})
   (ev {:event :var/end :ns 'my.app-test :var 'merge-test :pass 0 :duration-ms 340
        :output "debug line" :at 1352})
   (ev {:event :ns/end :ns 'my.app-test :duration-ms 352 :at 1352})
   (ev {:event :ns/start :ns 'other-test :worker 1 :at 1100})
   (ev {:event :var/start :ns 'other-test :var 'slow-test :worker 1 :at 1100})])

(def ^:private state
  (reduce rs/apply-event (rs/init {:namespaces '[my.app-test other-test later-test]}) events))

(deftest finished-namespace-line-formats
  (let [{:keys [emit]} (render/live-frame nil state opts)]
    (is (= ["   ✗ my.app-test/merge-test (test/my/app_test.clj:12)"
            " ✗ my.app-test (1/2 failed) 352ms"]
           emit))))

(deftest passing-namespace-line
  (let [passing (reduce rs/apply-event (rs/init {})
                        [(ev {:event :ns/start :ns 'p-test})
                         (ev {:event :var/start :ns 'p-test :var 'a})
                         (ev {:event :var/end :ns 'p-test :var 'a :pass 1 :duration-ms 3})
                         (ev {:event :ns/end :ns 'p-test :duration-ms 340})])]
    (is (= [" ✓ p-test (1 test) 340ms"] (:emit (render/live-frame nil passing opts))))
    (is (= [(str " " "\u001b[32m✓ p-test\u001b[0m \u001b[90m(1 test)\u001b[0m \u001b[90m340ms\u001b[0m")]
           (:emit (render/live-frame nil passing (assoc opts :color? true)))))))

(deftest live-frame-shows-running-block-and-footer
  (let [{:keys [frame emit]} (render/live-frame state state (assoc opts :now 3100))]
    (is (empty? emit) "nothing new since prev")
    (is (= [" ⠇ other-test › slow-test                                                 2.00s "
            ""
            " Namespaces  1 failed | 1 running | 1 pending (3)"
            "      Tests  1 passed | 1 failed | 1 running (3)"
            "   Duration  2.10s"]
           frame))))

(deftest ci-lines-print-failures-with-their-namespace
  (let [lines (render/ci-lines nil state opts)]
    (is (= " ✗ my.app-test (1/2 failed) 352ms" (first lines)))
    (is (some #(str/includes? % "FAIL  my.app-test/merge-test") lines))
    (is (not-any? #(str/includes? % "other-test") lines))))

(deftest failure-report-example
  (is (= (str/join
          "\n"
          ["━━━ Failed Tests ━━━"
           ""
           " FAIL  my.app-test/merge-test  test/my/app_test.clj:14"
           "   testing: outer > inner"
           "   merge broke"
           "   expected: {:a 1, :b {:c 2}}"
           "   actual:   {:a 1, :b {:c 3}, :d 4}"
           "   diff (- expected, + actual):"
           "     {"
           "       :a 1"
           "       :b {"
           "   -     :c 2"
           "   +     :c 3"
           "       }"
           "   +   :d 4"
           "     }"
           "   output:"
           "     | debug line"])
         (render/failure-report state opts))))

(deftest error-report-shows-class-data-and-trimmed-trace
  (let [st (reduce rs/apply-event (rs/init {})
                   [(ev {:event :ns/start :ns 'e-test})
                    (ev {:event :var/start :ns 'e-test :var 'boom})
                    (ev {:event :assertion :ns 'e-test :var 'boom :type :error
                         :error {:class "clojure.lang.ExceptionInfo" :message "bad"
                                 :data "{:x 1}"
                                 :trace (mapv #(str "frame" %) (range 12))
                                 :cause {:class "java.io.IOException" :message "disk" :trace []}}})])
        report (render/failure-report st opts)]
    (is (str/includes? report "clojure.lang.ExceptionInfo: bad"))
    (is (str/includes? report "data: {:x 1}"))
    (is (str/includes? report "at frame7"))
    (is (not (str/includes? report "at frame8")))
    (is (str/includes? report "… 4 more frames"))
    (is (str/includes? report "Caused by: java.io.IOException: disk"))))

(deftest summary-example
  (let [finished (reduce rs/apply-event state
                         [(ev {:event :var/end :ns 'other-test :var 'slow-test :worker 1
                               :pass 1 :duration-ms 900 :at 2000})
                          (ev {:event :ns/end :ns 'other-test :worker 1 :duration-ms 900 :at 2000})])]
    (is (= (str/join
            "\n"
            [""
             "3 tests | 2 passed | 1 failed | 0 errors | 0 skipped | 1 ns not run (1.00s)"
             ""
             "Slowest tests:"
             "  900ms  other-test/slow-test"
             "  340ms  my.app-test/merge-test"
             "   12ms  my.app-test/add-test"
             ""
             "Rerun failures:"
             "  brisk --focus my.app-test/merge-test"])
           (render/summary finished opts)))))

(deftest hostile-text-cannot-inject-escapes
  (let [st (reduce rs/apply-event (rs/init {})
                   [(ev {:event :ns/start :ns 'h-test})
                    (ev {:event :var/start :ns 'h-test :var 'x})
                    (ev {:event :assertion :ns 'h-test :var 'x :type :fail
                         :message "\u001b[2Jwipe\u0007\tend"})])]
    (testing "even with colour on, only our own SGR sequences appear"
      (is (not (re-find #"\u001b(?!\[[0-9;]*m)"
                        (render/failure-report st (assoc opts :color? true))))))
    (is (not (str/includes? (render/failure-report st opts) "\u001b")))))

(deftest truncation-keeps-wide-characters-inside-the-width
  (let [st (reduce rs/apply-event (rs/init {})
                   [(ev {:event :ns/start :ns 'h-test})
                    (ev {:event :var/start :ns 'h-test :var 'x})
                    (ev {:event :assertion :ns 'h-test :var 'x :type :fail
                         :message "日本語日本語日本語日本語日本語"})])]
    (doseq [w (range 0 30)]
      (is (every? #(<= (visible-width %) w)
                  (str/split-lines (render/failure-report st (assoc opts :width w))))))))

(deftest diff-examples
  (testing "vectors align on common elements"
    (is (= {:missing 1 :extra 0 :changed 0}
           (render/diff-stats (render/diff [1 2 3] [1 3])))))
  (testing "sets report members on each side"
    (is (= {:missing 1 :extra 1 :changed 0}
           (render/diff-stats (render/diff #{1 2} #{2 3})))))
  (testing "scalars of different types are a change"
    (is (= {:missing 0 :extra 0 :changed 1}
           (render/diff-stats (render/diff 1 1.0)))))
  (testing "unchanged entries around a change stay as context"
    (is (= ["  {" "    :a 1" "-   :b 2" "+   :b 3" "  }"]
           (render/diff-lines (render/diff {:a 1 :b 2} {:a 1 :b 3}) {:color? false})))))

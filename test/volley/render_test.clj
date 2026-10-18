(ns volley.render-test
  (:require
   [volley.gen.report :as gr]
   [volley.render :as render]
   [volley.run-state :as rs]
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
                  :crash (str/includes? report "worker exited")
                  :protocol-error (str/includes? report "could not be read")))
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
          done (->> (vals (:namespaces final))
                    (filter #(not= :running (rs/ns-result %)))
                    (remove rs/unselected?))]
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
             "3 tests | 2 passed | 1 failed | 0 errors | 0 skipped"
             "1 ns not run (4 assertions, 1.00s)"
             ""
             "Not run (did not finish):"
             "  later-test"
             ""
             "Slowest tests:"
             "  900ms  other-test/slow-test"
             "  340ms  my.app-test/merge-test"
             "   12ms  my.app-test/add-test"
             ""
             "Rerun failures:"
             "  volley --focus my.app-test/merge-test"])
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

(deftest documentation-lines-example
  (testing "kaocha.report/documentation: one line per var, grouped by namespace"
    (let [skipped (rs/apply-event state (ev {:event :var/skipped :ns 'my.app-test :var 'later-test
                                             :reason :pending}))]
      (is (= ["my.app-test 352ms"
              "  ✓ add-test 12ms"
              "  - later-test (skipped: pending)"
              "  ✗ merge-test 340ms"
              ""]
             (render/documentation-lines nil skipped opts)))
      (is (empty? (render/documentation-lines skipped skipped opts)) "append-only"))))

(defspec documentation-emits-each-finished-namespace-once runs
  (prop/for-all [r gr/run]
    (let [events (gr/events r 0)
          opts {:width 300 :color? false}
          lines (loop [prev nil st (rs/init (:plan r)) evs events out []]
                  (let [out (into out (render/documentation-lines prev st opts))]
                    (if-let [[e & more] (seq evs)]
                      (recur st (rs/apply-event st e) more out)
                      out)))
          final (gr/fold (:plan r) events)
          done (->> (vals (:namespaces final))
                    (filter #(not= :running (rs/ns-result %)))
                    (remove rs/unselected?))]
      (every? (fn [rec] (= 1 (count (filter #(re-find (re-pattern (str "^" (:ns rec) "( |$)")) %) lines))))
              done))))

(deftest only-equality-failures-get-a-diff
  (testing "(is (empty? xs)) reports the form and its value; diffing them means nothing"
    (let [st (reduce rs/apply-event (rs/init {})
                     [(ev {:event :ns/start :ns 'd-test})
                      (ev {:event :var/start :ns 'd-test :var 'x})
                      (ev {:event :assertion :ns 'd-test :var 'x :type :fail
                           :expected {:str "(empty? xs)" :edn '(empty? xs)}
                           :actual {:str "(not (empty? [1]))" :edn '(not (empty? [1]))}})])
          report (render/failure-report st opts)]
      (is (str/includes? report "expected: (empty? xs)"))
      (is (str/includes? report "actual:   (not (empty? [1]))"))
      (is (not (str/includes? report "diff (- expected"))))))

(deftest a-failed-property-shows-seed-size-input-and-rerun
  (let [state (reduce rs/apply-event (rs/init {:namespaces '[p.prop-test]})
                      [{:event :ns/start :ns 'p.prop-test :worker 1 :at 0}
                       {:event :var/start :ns 'p.prop-test :var 'small :worker 1 :at 1
                        :file "test/p/prop_test.clj" :line 6}
                       {:event :assertion :ns 'p.prop-test :var 'small :type :fail
                        :file "test/p/prop_test.clj" :line 6 :at 2
                        :expected {:str "{:result true}" :edn {:result true}}
                        :actual {:str "{:shrunk ... huge map ...}"}
                        :check {:seed 42 :num-tests 7 :failing-size 6
                                :smallest {:str "[3]" :edn [3]} :fail {:str "[5]" :edn [5]}}}
                       {:event :var/end :ns 'p.prop-test :var 'small :worker 1 :at 3 :pass 0}
                       {:event :ns/end :ns 'p.prop-test :worker 1 :at 4}])
        report (render/failure-report state {:width 120 :color? false})]
    (is (str/includes? report "property failed after 7 tests (seed 42, size 6)"))
    (is (str/includes? report "smallest failing input: [3]"))
    (is (str/includes? report "rerun: (p.prop-test/small 7 :seed 42)"))
    (is (not (str/includes? report "huge map")) "the raw result map is not printed")))

;;; Values in full

(defspec pretty-values-read-back-as-themselves runs
  (prop/for-all [v gr/edn-value
                 width (gen/choose 10 120)]
    (= v (read-string (str/join "\n" (render/pretty v width))))))

(def ^:private small-data
  "Nested data of short scalars, at most a few levels deep, which can always
  be laid out within 40 columns."
  (gen/scale #(min % 12)
             (gen/recursive-gen
              (fn [inner]
                (gen/one-of [(gen/vector inner 0 6) (gen/list inner) (gen/set inner {:max-elements 4})
                             (gen/map inner inner {:max-elements 4})]))
              (gen/one-of [(gen/choose -99 99) (gen/elements [:a :bb :ccc "s" "tt" 'x nil true])]))))

(defn- depth [v] (if (and (coll? v) (seq v)) (inc (apply max (map depth (if (map? v) (mapcat identity v) v)))) 0))

(defspec pretty-lines-fit-where-the-structure-allows runs
  (prop/for-all [v (gen/such-that #(<= (depth %) 4) small-data)
                 width (gen/choose 40 120)]
    (every? #(<= (count %) width) (render/pretty v width))))

(defn- one-failure [assertion output]
  (reduce rs/apply-event (rs/init {:namespaces '[v-test]})
          [(ev {:event :ns/start :ns 'v-test})
           (ev {:event :var/start :ns 'v-test :var 'x})
           (ev (merge {:event :assertion :ns 'v-test :var 'x :type :fail} assertion))
           (ev {:event :var/end :ns 'v-test :var 'x :pass 0 :fail 1 :duration-ms 1 :output output})
           (ev {:event :ns/end :ns 'v-test})]))

(deftest values-are-shown-in-full-and-wrapped
  (let [big (vec (range 60))
        long-str (apply str (repeat 200 "x"))
        report (render/failure-report
                (one-failure {:expected {:str (pr-str big) :edn big}
                              :actual {:str (pr-str long-str) :edn long-str}}
                             nil)
                {:width 60 :color? false})
        lines (str/split-lines report)]
    (is (every? #(<= (count %) 60) lines) "wrapped to the width, not cut")
    (is (not (str/includes? report "…")))
    (is (str/includes? report "59]") "the end of the vector is there")
    (is (str/includes? (str/replace report #"\n\s+" "") long-str) "every character of the string is there")))

(deftest the-tail-of-long-output-is-shown-with-the-right-count
  (let [output (str/join "\n" (map #(str "line " %) (range 100)))
        report (render/failure-report (one-failure {:message "boom"} output) {:width 80 :color? false})]
    (is (str/includes? report "  | … 70 earlier lines"))
    (is (str/includes? report "  | line 99"))
    (is (str/includes? report "  | line 70"))
    (is (not (str/includes? report "  | line 69")))))

(defn- failing-vars [n]
  (reduce rs/apply-event (rs/init {:namespaces '[r-test]})
          (concat [(ev {:event :ns/start :ns 'r-test})]
                  (mapcat (fn [i]
                            (let [v (symbol (str "t" i))]
                              [(ev {:event :var/start :ns 'r-test :var v})
                               (ev {:event :assertion :ns 'r-test :var v :type :fail})
                               (ev {:event :var/end :ns 'r-test :var v :pass 0 :fail 1 :duration-ms 1})]))
                          (range n))
                  [(ev {:event :ns/end :ns 'r-test})])))

(deftest the-rerun-hint-says-what-it-leaves-out
  (let [text (render/summary (failing-vars 23) {:width 80 :color? false :invocation "bb test"})]
    (is (str/includes? text "  bb test \\"))
    (is (= 20 (count (re-seq #"--focus r-test/t" text))))
    (is (str/includes? text "… and 3 more failures not listed")))
  (testing "the summary says 1 error, 1 test"
    (let [text (render/summary (failing-vars 1) {:width 200 :color? false})]
      (is (str/includes? text "1 test | 0 passed | 1 failed | 0 errors"))
      (is (str/includes? text "  volley --focus r-test/t0") "volley when the shell did not say"))))

(deftest a-qualified-equals-is-taken-apart-too
  (let [actual (list 'not (list 'clojure.core/= 1 2))
        report (render/failure-report
                (reduce rs/apply-event (rs/init {:namespaces '[q-test]})
                        [(ev {:event :ns/start :ns 'q-test})
                         (ev {:event :var/start :ns 'q-test :var 'x})
                         (ev {:event :assertion :ns 'q-test :var 'x :type :fail
                              :expected {:str "(clojure.core/= 1 2)" :edn (list 'clojure.core/= 1 2)}
                              :actual {:str (pr-str actual) :edn actual}})])
                {:width 80 :color? false})]
    (is (str/includes? report "expected: 1\n"))
    (is (str/includes? report "actual:   2"))))

(deftest stray-output-keeps-its-length-but-not-its-escapes
  ;; probe liveout: only the redrawn frame has to fit the terminal
  (let [long-line (apply str (repeat 30 "abcdef "))]
    (is (= [long-line "next"] (render/stray-lines (str long-line "\nnext") {:width 40})))
    (is (= ["red text" "x"] (render/stray-lines "\u001b[31mred text\u001b[0m\r\nx\u0007" {:width 40})))))

(deftest watch-mode-ends-each-run-with-a-compact-summary
  (let [state (reduce rs/apply-event (rs/init {:namespaces '[a-test]})
                      [{:event :ns/start :ns 'a-test :worker 1 :at 0}
                       {:event :var/start :ns 'a-test :var 'x :worker 1 :at 1}
                       {:event :assertion :ns 'a-test :var 'x :type :fail :worker 1 :at 2}
                       {:event :var/end :ns 'a-test :var 'x :pass 0 :fail 1 :duration-ms 5 :worker 1 :at 3}
                       {:event :ns/end :ns 'a-test :duration-ms 6 :worker 1 :at 4}])
        full (render/summary state {:width 80})
        compact (render/summary state {:width 80 :compact? true})]
    (is (str/includes? full "Rerun failures"))
    (is (str/includes? full "Slowest tests"))
    (is (not (str/includes? compact "Rerun failures")))
    (is (not (str/includes? compact "Slowest tests")))
    (is (str/includes? compact "1 test | 0 passed | 1 failed"))))

(ns volley.golden-test
  "Failure rendering from real workers: the `dev-resources/exec` fixtures run
  in worker processes, their events are folded into a run state, and the
  report and summary are checked for the parts a reader needs."
  (:require
   [volley.exec-harness :as harness]
   [volley.render :as render]
   [volley.run-state :as rs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def ^:private plan
  '{:parallel [exec.fail-test exec.error-test exec.output-test exec.contexts-test
               exec.exit-test exec.load-error-test exec.pass-test]
    :serial []})

(def ^:private opts {:width 120 :color? false})

(def ^:private run
  (delay
    (let [{:keys [events timed-out?]} (harness/run-plan plan {:workers 3})]
      (assert (not timed-out?))
      (reduce rs/apply-event
              (rs/init {:namespaces (:parallel plan)})
              events))))

(defn- report [] (render/failure-report @run opts))

(defn- section
  "The report entry whose header line contains `header`, up to the next one."
  [header]
  (let [entries (str/split (report) #"\n\n")]
    (first (filter #(str/includes? (first (str/split-lines %)) header) entries))))

(deftest equality-failure-shows-a-structural-diff
  (let [s (section "FAIL  exec.fail-test/nested-mismatch")]
    (is (some? s))
    (is (str/includes? s "expected: {:a [1 2 {:b 3}], :c #{1 2}}"))
    (is (str/includes? s "actual:   {:a [1 2 {:b 4}], :c #{1 2}}"))
    (is (str/includes? s "diff (- expected, + actual):"))
    (is (re-find #"(?m)^   -\s+:b 3$" s))
    (is (re-find #"(?m)^   \+\s+:b 4$" s))))

(deftest error-assertions-show-class-message-and-trace
  (let [s (section "ERROR  exec.error-test/throws ")]
    (is (some? s))
    (is (str/includes? s "actual:   clojure.lang.ExceptionInfo: boom"))
    (is (str/includes? s "data: {:why :testing}"))
    ;; On babashka every frame below the throw belongs to the interpreter
    ;; (sci.*), which the worker trims as runner frames, so there may be no
    ;; trace to show. On the JVM the user's frames appear here.
    (is (not (re-find #"(?m)^\s+at (sci|babashka|clojure\.test)" s))))
  (let [s (section "ERROR  exec.error-test/throws-inside-is")]
    (is (str/includes? s "java.lang.NumberFormatException"))))

(deftest captured-output-appears-under-the-failing-test
  (let [s (section "FAIL  exec.output-test/prints-via-system-out")]
    (is (some? s))
    (testing "System/out is captured too, as with kaocha.plugin.capture-output"
      (is (str/includes? s "| direct system out")))
    (is (not (str/includes? (report) "hello from println"))
        "output of passing tests is not shown")))

(deftest testing-contexts-are-shown
  (let [s (section "FAIL  exec.contexts-test/nested-contexts")]
    (is (str/includes? s "testing: outer > inner"))
    (is (str/includes? s "with message"))))

(deftest crashes-and-load-errors-are-reported-once
  (let [fs (rs/failures @run)]
    (is (= 1 (count (filter #(= :crash (:kind %)) fs))))
    (is (str/includes? (report) "CRASH  exec.exit-test/kills-the-worker  worker exited with code 3"))
    (is (str/includes? (report) "ERROR  exec.load-error-test  failed to load"))
    (is (str/includes? (report) "java.io.FileNotFoundException"))))

(deftest summary-counts-real-run
  (let [s (render/summary @run opts)]
    (is (re-find #"\d+ tests \| \d+ passed \| 4 failed \| 3 errors \| 0 skipped \| 2 ns failed to run" s)
        s)
    (is (str/includes? s "--focus exec.fail-test/nested-mismatch"))))

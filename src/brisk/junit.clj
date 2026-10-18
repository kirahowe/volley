(ns brisk.junit
  "Run state to an Ant JUnit XML report, in the shape the
  `kaocha.plugin/junit-xml` plugin (lambdaisland/kaocha-junit-xml 1.17.101)
  writes:

      <testsuites>
        <testsuite name=\"unit\" id=\"0\" hostname=\"localhost\" errors=\"..\"
                   failures=\"..\" tests=\"..\" timestamp=\"..\" time=\"..\" package=\"\">
          <properties/>
          <testcase name=\"my.ns/my-test\" classname=\"my.ns\" time=\"..\">
            <failure message=\"[:fail] expected: ... actual: ...\"
                     type=\"assertion failure\">text</failure>
            <error message=\"..\" type=\"java.lang.Exception\">text</error>
          </testcase>
          <system-out>captured output</system-out>
          <system-err/>
        </testsuite>
      </testsuites>

  One `<testsuite>` per suite of the plan, as Kaocha writes one per suite,
  and a `<testcase>` per var the summary counts. Like Kaocha, `errors` and
  `failures` count assertions. Failures that belong to no var (a namespace
  that failed to load, a crash, an unreadable file, a namespace that never
  ran) get a testcase of their own, as Kaocha reports a failed namespace
  as a testcase. Skipped vars carry `<skipped/>`, which Kaocha leaves out.

  Pure: the caller passes the timestamp and working directory."
  (:require
   [brisk.run-state :as rs]
   [clojure.string :as str]))

;;; XML text

(def ^:private invalid-xml-chars
  "Characters XML 1.0 does not allow, lone surrogates included."
  #"[^\x09\x0A\x0D\x20-\uD7FF\uE000-\uFFFD\x{10000}-\x{10FFFF}]")

(def ^:private ansi-re #"\u001b\[[\x30-\x3F]*[\x20-\x2F]*[\x40-\x7E]")

(defn- clean [s]
  (-> (str s)
      (str/replace ansi-re "")
      (str/replace invalid-xml-chars "")))

(defn escape
  "Text made safe for XML content and attribute values: characters XML
  cannot hold and ANSI escape sequences are removed, the five special
  characters become entities, and in attributes newlines and tabs become
  character references so they survive attribute normalization."
  ([s] (escape s false))
  ([s attr?]
   (cond-> (str/escape (clean s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&apos;"})
     attr? (str/escape {\newline "&#10;" \return "&#13;" \tab "&#9;"}))))

(defn- element
  "`[tag attrs & children]` as XML text. `attrs` is a map or a sequence of
  pairs (to keep their order); children are elements or strings."
  [[tag attrs & children]]
  (let [attrs (str/join (for [[k v] attrs :when (some? v)]
                          (str " " (name k) "=\"" (escape v true) "\"")))
        body (keep (fn [c] (cond (string? c) (escape c)
                                 (vector? c) (element c)))
                   children)]
    (if (seq children)
      (str "<" (name tag) attrs ">" (str/join body) "</" (name tag) ">")
      (str "<" (name tag) attrs "/>"))))

;;; Values

(defn seconds
  "Milliseconds as seconds with six decimals, independent of the locale."
  [ms]
  (let [micros (Math/round (* 1000.0 (double (or ms 0))))
        frac (str (rem micros 1000000))]
    (str (quot micros 1000000) "." (str/join (repeat (- 6 (count frac)) "0")) frac)))

(defn- value-str [v]
  (cond
    (nil? v) "nil"
    (and (map? v) (contains? v :str)) (:str v)
    :else (pr-str v)))

(defn- location [{:keys [file line]}]
  (when file (str file (when line (str ":" line)))))

(defn- error-text [{:keys [class message data trace cause]}]
  (str/join "\n" (concat [(str class (when message (str ": " message)))]
                         (when data [(str "data: " data)])
                         (map #(str "  at " %) trace)
                         (when cause [(str "Caused by: " (error-text cause))]))))

(defn- detail
  "The text inside a failure or error element, like Kaocha's
  `kaocha.report/fail-summary`."
  [{:keys [kind ns var message contexts expected actual error output] :as f}]
  (str/join "\n" (concat [(str (if (= :fail kind) "FAIL" "ERROR") " in "
                               (cond-> (str ns) var (str "/" var))
                               (when-let [l (location f)] (str " (" l ")")))]
                         (when (seq contexts) [(str/join " " contexts)])
                         (when (seq message) [message])
                         (when (or expected actual)
                           [(str "expected: " (value-str expected))
                            (str "  actual: " (value-str actual))])
                         (when error [(error-text error)])
                         (when (seq output) ["output:" output]))))

(defn- failure-element [f]
  (if (= :fail (:kind f))
    [:failure {:message (str "[:fail] expected: " (value-str (:expected f))
                             ". actual: " (value-str (:actual f)))
               :type "assertion failure"}
     (detail f)]
    [:error {:message (or (:message f) (get-in f [:error :message]) (:reason f))
             :type (or (get-in f [:error :class]) ":error")}
     (detail f)]))

(defn- other-failure
  "A failure that belongs to no var, as an error with a description."
  [{:keys [kind ns file worker error reason exit-code] :as f}]
  (let [message (case kind
                  :ns-error (case (:phase f) :run "failed in a fixture" :setup "not run: worker setup failed" "failed to load")
                  :crash (str "worker exited with code " exit-code)
                  :file-error "ns form could not be read"
                  :worker-error (str "worker " worker ": a hook failed as it shut down")
                  :protocol-error (str "a message from worker " worker " could not be read")
                  :not-run (str "not run: " (name reason))
                  (name kind))]
    {:kind :error :ns (or ns file (str "worker " worker)) :message message
     :error (or error (when (:message f) {:class "brisk.Error" :message (:message f) :trace []}))
     :output (:output f)}))

;;; Report

(defn- var-failures [failures ns var]
  (filter #(and (= ns (:ns %)) (= var (:var %)) (#{:fail :error} (:kind %))) failures))

(defn- testcase [failures cwd add-location? {:keys [ns var duration-ms file line] :as v}]
  (into [:testcase (cond-> {:name (str ns "/" var) :classname (str ns)
                            :time (seconds duration-ms)}
                     (and add-location? file) (assoc :file (str cwd "/" file) :line (str line)))]
        (concat (when (= :skipped (rs/var-status v)) [[:skipped {}]])
                (when (= :crashed (rs/var-status v))
                  [[:error {:message "the worker process crashed while this test ran"
                            :type "brisk.WorkerCrashed"}]])
                (map failure-element (var-failures failures ns var)))))

(defn- failure-testcase [f]
  (let [g (other-failure f)]
    [:testcase {:name (str (:ns g)) :classname "" :time (seconds 0)}
     (failure-element g)]))

(defn- outside-testcase
  "An assertion a `:once` fixture made, outside every test."
  [f]
  [:testcase {:name (str (:ns f)) :classname "" :time (seconds 0)}
   (failure-element f)])

(defn- suite-element
  [run failures {:keys [omit-system-out? add-location? timestamp cwd]} idx {:keys [id namespaces files]}]
  (let [nses (set namespaces)
        recs (keep #(get-in run [:namespaces %]) namespaces)
        counted (remove rs/unselected? recs)
        vars (sort-by (juxt (comp str :ns) (comp str :var)) (mapcat (comp vals :vars) counted))
        outside (filter #(and (nil? (:var %)) (#{:fail :error} (:kind %)) (contains? nses (:ns %))) failures)
        others (filter (fn [f]
                         (and (not (#{:fail :error} (:kind f)))
                              (not (and (= :crash (:kind f)) (:var f)))
                              (or (contains? nses (:ns f))
                                  (contains? (set files) (:file f))
                                  (and (zero? idx) (nil? (:ns f)) (nil? (:file f))))))
                       failures)
        counts (concat (map rs/var-counts vars) (map rs/ns-counts counted))
        sum (fn [k] (reduce + (map k counts)))
        time-ms (reduce + (keep #(or (:duration-ms %) (when (and (:start-at %) (:end-at %))
                                                         (- (:end-at %) (:start-at %))))
                                recs))
        output (when-not omit-system-out?
                 (->> vars (keep :output) (remove str/blank?) seq))]
    (-> [:testsuite [[:name (name id)] [:id (str idx)] [:hostname "localhost"]
                     [:errors (str (+ (sum :error) (count others)
                                      (count (filter #(= :crashed (rs/var-status %)) vars))))]
                     [:failures (str (sum :fail))]
                     [:tests (str (+ (count vars) (count outside) (count others)))]
                     [:timestamp timestamp]
                     [:time (seconds time-ms)]
                     [:package ""]]
         [:properties {}]]
        (into (map #(testcase failures cwd add-location? %) vars))
        (into (map outside-testcase outside))
        (into (map failure-testcase others))
        (conj (into [:system-out {}] (map #(str % "\n") output))
              [:system-err {}]))))

(defn report
  "The JUnit XML document for run state `run`. `suites` is the plan's
  suites in order, `[{:id kw :namespaces [ns ...] :files [path ...]}]`
  (`:files` are the suite's unreadable test files). opts:
  `:timestamp` (ISO local date-time string), `:cwd` (for absolute file
  paths), `:omit-system-out?`, `:add-location?` (Kaocha's
  `--junit-xml-omit-system-out` and `--junit-xml-add-location-metadata`)."
  [run suites opts]
  (let [failures (rs/failures run)]
    (str "<?xml version='1.0' encoding='UTF-8'?>\n"
         (element (into [:testsuites {}]
                        (map-indexed #(suite-element run failures opts %1 %2) suites)))
         "\n")))

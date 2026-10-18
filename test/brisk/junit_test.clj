(ns brisk.junit-test
  (:require
   [brisk.gen.report :as gr]
   [brisk.junit :as junit]
   [brisk.run-state :as rs]
   [clojure.data.xml :as xml]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

(def ^:private runs 100)

(defn- parse [text] (xml/parse-str text))

(defn- children [el tag] (filter #(and (map? %) (= tag (:tag %))) (:content el)))

(defn- text-of [el] (apply str (filter string? (:content el))))

(defn- suites-of [state]
  [{:id :unit :namespaces (vec (sort-by str (keys (:namespaces state)))) :files (vec (keys (:file-errors state)))}])

(def ^:private opts {:timestamp "2026-10-18T10:00:00" :cwd "/work"})

(defspec reports-are-well-formed-xml runs
  (prop/for-all [state gr/state
                 add-location? gen/boolean]
    (let [doc (parse (junit/report state (suites-of state) (assoc opts :add-location? add-location?)))]
      (and (= :testsuites (:tag doc))
           (= 1 (count (children doc :testsuite)))))))

(defspec testcases-match-the-summary runs
  (prop/for-all [state gr/state]
    (let [suite (first (children (parse (junit/report state (suites-of state) opts)) :testsuite))
          cases (children suite :testcase)
          {:keys [vars assertions]} (rs/summary state)
          var-cases (filter #(str/includes? (get-in % [:attrs :name]) "/") cases)]
      (and (= (:total vars) (count var-cases))
           (= (str (count cases)) (get-in suite [:attrs :tests]))
           (= (str (:failed assertions)) (get-in suite [:attrs :failures]))
           (= (count (filter #(= :skipped (rs/var-status %)) (mapcat (comp vals :vars) (remove rs/unselected? (vals (:namespaces state))))))
              (count (mapcat #(children % :skipped) var-cases)))
           (= (count (filter #(#{:fail} (:kind %)) (rs/failures state)))
              (count (mapcat #(children % :failure) cases)))))))

(defn- newlines
  "Line ends as an XML parser reports them in text."
  [s]
  (-> s (str/replace "\r\n" "\n") (str/replace "\r" "\n")))

(defspec any-text-survives-escaping 300
  (prop/for-all [s (gen/one-of [gen/string gr/hostile-text
                                (gen/fmap #(apply str %) (gen/vector (gen/fmap char (gen/choose 0 0xFFFF)) 0 40))])]
    (let [doc (parse (str "<a b=\"" (junit/escape s true) "\">" (junit/escape s) "</a>"))
          kept (-> s
                   (str/replace #"\u001b\[[\x30-\x3F]*[\x20-\x2F]*[\x40-\x7E]" "")
                   (str/replace #"[^\x09\x0A\x0D\x20-\uD7FF\uE000-\uFFFD\x{10000}-\x{10FFFF}]" ""))]
      (and (= kept (get-in doc [:attrs :b]))
           ;; data.xml 0.0.8 (the JVM's) drops text that is only whitespace
           (or (= (newlines kept) (newlines (text-of doc)))
               (and (re-matches #"[ \t\r\n]*" kept) (= "" (text-of doc))))))))

(deftest seconds-are-locale-independent
  (is (= "0.000000" (junit/seconds nil)))
  (is (= "1.234500" (junit/seconds 1234.5)))
  (is (= "61.000000" (junit/seconds 61000))))

(defn- ev [m] (merge {:worker 1 :at 1000} m))

(deftest report-example-matches-kaocha-junit-xml
  (let [state (reduce rs/apply-event (rs/init {:namespaces '[a-test b-test]})
                      [(ev {:event :ns/start :ns 'a-test})
                       (ev {:event :var/start :ns 'a-test :var 'ok :file "test/a_test.clj" :line 3})
                       (ev {:event :var/end :ns 'a-test :var 'ok :pass 2 :duration-ms 12 :output "hello <world>"})
                       (ev {:event :var/start :ns 'a-test :var 'bad :file "test/a_test.clj" :line 7})
                       (ev {:event :assertion :ns 'a-test :var 'bad :type :fail :message "sums"
                            :expected {:str "(= 3 (+ 1 1))"} :actual {:str "(not (= 3 2))"}
                            :file "test/a_test.clj" :line 8})
                       (ev {:event :assertion :ns 'a-test :var 'bad :type :error
                            :expected {:str "nil"} :actual {:str "java.lang.Exception: boom"}
                            :error {:class "java.lang.Exception" :message "boom" :trace ["a.b (x.clj:1)"]}})
                       (ev {:event :var/end :ns 'a-test :var 'bad :pass 0 :fail 1 :error 1 :duration-ms 3})
                       (ev {:event :var/skipped :ns 'a-test :var 'later :reason :pending})
                       (ev {:event :ns/end :ns 'a-test :duration-ms 20})
                       (ev {:event :ns/start :ns 'b-test :worker 2})
                       (ev {:event :ns/error :ns 'b-test :worker 2 :phase :load
                            :error {:class "java.io.FileNotFoundException" :message "no such ns" :trace []}})
                       (ev {:event :ns/end :ns 'b-test :worker 2 :duration-ms 5})])
        doc (parse (junit/report state [{:id :unit :namespaces '[a-test b-test] :files []}]
                                 (assoc opts :add-location? true)))
        [suite] (children doc :testsuite)
        cases (children suite :testcase)
        by-name (into {} (map (juxt (comp :name :attrs) identity)) cases)]
    (testing "kaocha.plugin.junit-xml/suite->xml: one testsuite per suite, assertion counts"
      (is (= {:name "unit" :id "0" :hostname "localhost" :errors "2" :failures "1" :tests "4"
              :timestamp "2026-10-18T10:00:00" :time "0.025000" :package ""}
             (:attrs suite)))
      (is (= [:properties :testcase :testcase :testcase :testcase :system-out :system-err]
             (map :tag (filter map? (:content suite))))))
    (testing "testcase->xml: name ns/var, classname ns, time, location"
      (is (= {:name "a-test/ok" :classname "a-test" :time "0.012000" :file "/work/test/a_test.clj" :line "3"}
             (:attrs (by-name "a-test/ok")))))
    (testing "failure->xml and error->xml"
      (let [bad (by-name "a-test/bad")
            [f] (children bad :failure)
            [e] (children bad :error)]
        (is (= {:message "[:fail] expected: (= 3 (+ 1 1)). actual: (not (= 3 2))" :type "assertion failure"}
               (:attrs f)))
        (is (str/starts-with? (text-of f) "FAIL in a-test/bad (test/a_test.clj:8)\nsums\nexpected:"))
        (is (= {:message "boom" :type "java.lang.Exception"} (:attrs e)))))
    (testing "skipped and namespace-level failures"
      (is (= [:skipped] (map :tag (filter map? (:content (by-name "a-test/later"))))))
      (is (= {:message "failed to load" :type "java.io.FileNotFoundException"}
             (:attrs (first (children (by-name "b-test") :error))))))
    (testing "captured output goes to system-out"
      (is (= "hello <world>\n" (text-of (first (children suite :system-out))))))))

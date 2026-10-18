(ns brisk.discover-test
  (:require
   [brisk.discover :as discover]
   [brisk.gen.compat :as g]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

;; ---------------------------------------------------------------------------
;; Properties

(defspec ns-info-never-throws 300
  (prop/for-all [text gen/string
                 path (gen/elements ["a.clj" "test/x_test.clj"])]
    (map? (discover/ns-info path text))))

(defspec ns-info-never-throws-on-truncated-files 200
  (prop/for-all [{:keys [text]} g/ns-file
                 cut gen/nat]
    (map? (discover/ns-info "t.clj" (subs text 0 (min cut (count text)))))))

(defspec ns-form-round-trips 300
  (prop/for-all [{:keys [text] :as expected} g/ns-file]
    (let [info (discover/ns-info "test/x_test.clj" text)]
      (and (nil? (:error info))
           (nil? (:scan-error info))
           (= (select-keys expected [:ns :requires :as-alias-only :meta :vars])
              (select-keys info [:ns :requires :as-alias-only :meta :vars]))))))

(defspec as-alias-only-never-overlaps-requires 200
  (prop/for-all [{:keys [text]} g/ns-file]
    (let [{:keys [requires as-alias-only]} (discover/ns-info "x.clj" text)]
      (empty? (filter requires as-alias-only)))))

(defspec test-file-requires-a-test-path 200
  (prop/for-all [dir (gen/elements ["test" "test/unit" "./test" "src"])
                 file g/segment]
    (let [suite {:test-paths ["test"]}
          path (str dir "/" file ".clj")]
      (= (boolean (re-find #"^(\./)?test/" path))
         (discover/test-file? suite path)))))

(defspec ns-match-is-re-find-on-the-name 200
  (prop/for-all [nm g/ns-sym
                 pat (gen/elements ["-test$" "^app\\." "core" ".*" "^$"])]
    (= (boolean (re-find (re-pattern pat) (str nm)))
       (discover/ns-match? {:ns-patterns [pat]} nm))))

;; ---------------------------------------------------------------------------
;; Examples

(def ^:private sample
  "#!/usr/bin/env bb
(ns ^:synchronized my.app-test
  \"doc\"
  {:author \"x\" :slow true}
  (:require [clojure.test :as t :refer [deftest is]]
            [my.app :as app]
            [my.specs :as-alias specs]
            (my.pre fix [x :as y] z)
            #?(:cljs [nope :as n] :clj [yes :as y2])
            plain
            :reload)
  (:use [use.me :only [a]])
  (:import (java.io File)))
(require '[late.req :as lr] 'another)
(t/deftest ^:slow foo (is (= ::specs/x 1)) #\"re\" `(a ~b) #(inc %) @x #'y)
(deftest ^{:kaocha/skip true :x 1} bar)
(deftest plain-one)
(do (deftest in-do))
(comment (deftest ignored))
")

(deftest ns-form-test
  (let [info (discover/ns-info "test/my/app_test.clj" sample)]
    (testing "name, metadata (reader meta on the name, attr-map, docstring)"
      (is (= 'my.app-test (:ns info)))
      (is (= {:synchronized true :author "x" :slow true :doc "doc"} (:meta info))))
    (testing "every require form, with prefix lists and reader conditionals"
      (is (= '#{clojure.test my.app my.pre.fix my.pre.x my.pre.z yes plain use.me
                late.req another}
             (:requires info))))
    (testing ":as-alias does not load the namespace"
      (is (= '#{my.specs} (:as-alias-only info))))
    (testing "literal metadata on deftest names, in any deftest spelling"
      (is (= '{foo {:slow true}
               bar {:kaocha/skip true :x 1}
               plain-one {}
               in-do {}}
             (:vars info))))
    (is (nil? (:error info)))))

(deftest tolerant-reader-test
  (testing "auto-resolved keywords, regexes, syntax-quote, unknown tags"
    (let [info (discover/ns-info "x.clj" "(ns a (:require [b])) (def x #foo/bar 1) (def y ::z/k) (def r #\"a+\")")]
      (is (= 'a (:ns info)))
      (is (nil? (:scan-error info)))))
  (testing "ns names with reader conditionals in the ns form"
    (is (= '#{clojure.string}
           (:requires (discover/ns-info "x.cljc" "(ns a (:require #?(:clj [clojure.string] :cljs [goog.string])))"))))))

(deftest unparseable-test
  (testing "an unreadable ns form is an :error, not an exception"
    (let [info (discover/ns-info "test/bad_test.clj" "(ns foo (:require [a.b")]
      (is (= "test/bad_test.clj" (:file info)))
      (is (string? (:error info)))
      (is (nil? (:ns info)))))
  (testing "a file with a good ns form but a broken tail keeps its ns"
    (let [info (discover/ns-info "x.clj" "(ns foo) (deftest a) {:a")]
      (is (= 'foo (:ns info)))
      (is (= {'a {}} (:vars info)))
      (is (string? (:scan-error info)))))
  (testing "no ns form at all"
    (is (= {:file "x.clj"} (discover/ns-info "x.clj" "(println 1)")))
    (is (= {:file "x.clj"} (discover/ns-info "x.clj" "")))))

(deftest suite-membership-test
  (let [suite {:id :unit :test-paths ["test"] :ns-patterns ["-test$"]}]
    (testing "kaocha.load/ns-match?: re-find against the namespace name"
      (is (discover/ns-match? suite 'my.app-test))
      (is (not (discover/ns-match? suite 'my.app-test-helpers)))
      (is (discover/ns-match? {:ns-patterns ["-test$" "^it\\."]} 'it.thing)))
    (testing "test-file? needs a test path and a Clojure extension"
      (is (discover/test-file? suite "test/a/b_test.clj"))
      (is (discover/test-file? suite "test/a/b_test.cljc"))
      (is (discover/test-file? suite "./test/a_test.clj"))
      (is (not (discover/test-file? suite "test/a/data.edn")))
      (is (not (discover/test-file? suite "src/a_test.clj")))
      (is (not (discover/test-file? suite "testing/a_test.clj"))))
    (testing "kaocha.load/find-test-nss: unreadable ns forms are kept so they get reported"
      (is (discover/suite-member? suite {:file "test/x.clj" :error "boom"}))
      (is (not (discover/suite-member? suite {:file "test/x.clj"})))
      (is (not (discover/suite-member? suite {:file "src/x.clj" :ns 'x-test}))))
    (testing "mark-tests"
      (is (= [true false]
             (mapv :test? (discover/mark-tests [suite]
                                               [{:file "test/a_test.clj" :ns 'a-test}
                                                {:file "src/a.clj" :ns 'a}])))))))

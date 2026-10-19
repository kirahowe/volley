(ns volley.discover-test
  (:require
   [volley.discover :as discover]
   [volley.gen.compat :as g]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check :as tc]
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

(def ^:private round-trip
  (prop/for-all [{:keys [text] :as expected} g/ns-file]
    (let [info (discover/ns-info "test/x_test.clj" text)]
      (and (nil? (:error info))
           (nil? (:scan-error info))
           (= (select-keys expected [:ns :requires :as-alias-only :meta :vars])
              (select-keys info [:ns :requires :as-alias-only :meta :vars]))))))

(defspec ns-form-round-trips 300 round-trip)

(deftest ns-form-round-trips-at-a-seed-that-generated-nil
  ;; Seed 9 once generated `(deftest nil ...)`: the generator expected a var
  ;; named by the symbol `nil`, which reads back as the literal nil.
  (is (:pass? (tc/quick-check 300 round-trip :seed 9 :max-size 200))))

(deftest a-var-or-lib-named-nil-is-not-a-symbol
  (let [info (discover/ns-info "x.clj" "(ns a (:require [nil :as-alias l] [true :as t]))\n(deftest nil (is true))")]
    (is (= {} (:vars info)))
    (is (= #{} (:as-alias-only info)))
    (is (not (contains? (:requires info) nil)))))

(defspec as-alias-only-never-overlaps-requires 200
  (prop/for-all [{:keys [text]} g/ns-file]
    (let [{:keys [requires as-alias-only]} (discover/ns-info "x.clj" text)]
      (empty? (filter requires as-alias-only)))))

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

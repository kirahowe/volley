(ns volley.paths-test
  (:require
   [volley.gen.compat :as g]
   [volley.paths :as paths]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

(defspec test-file-requires-a-test-path 200
  (prop/for-all [dir (gen/elements ["test" "test/unit" "./test" "src"])
                 file g/segment]
    (let [suite {:test-paths ["test"]}
          path (str dir "/" file ".clj")]
      (= (boolean (re-find #"^(\./)?test/" path))
         (paths/test-file? suite path)))))

(defspec ns-match-is-re-find-on-the-name 200
  (prop/for-all [nm g/ns-sym
                 pat (gen/elements ["-test$" "^app\\." "core" ".*" "^$"])]
    (= (boolean (re-find (re-pattern pat) (str nm)))
       (paths/ns-match? {:ns-patterns [pat]} nm))))

(deftest suite-membership-test
  (let [suite {:id :unit :test-paths ["test"] :ns-patterns ["-test$"]}]
    (testing "kaocha.load/ns-match?: re-find against the namespace name"
      (is (paths/ns-match? suite 'my.app-test))
      (is (not (paths/ns-match? suite 'my.app-test-helpers)))
      (is (paths/ns-match? {:ns-patterns ["-test$" "^it\\."]} 'it.thing)))
    (testing "test-file? needs a test path and a Clojure extension"
      (is (paths/test-file? suite "test/a/b_test.clj"))
      (is (paths/test-file? suite "test/a/b_test.cljc"))
      (is (paths/test-file? suite "./test/a_test.clj"))
      (is (not (paths/test-file? suite "test/a/data.edn")))
      (is (not (paths/test-file? suite "src/a_test.clj")))
      (is (not (paths/test-file? suite "testing/a_test.clj"))))
    (testing "kaocha.load/find-test-nss: unreadable ns forms are kept so they get reported"
      (is (paths/suite-member? suite {:file "test/x.clj" :error "boom"}))
      (is (not (paths/suite-member? suite {:file "test/x.clj"})))
      (is (not (paths/suite-member? suite {:file "src/x.clj" :ns 'x-test}))))
    (testing "mark-tests"
      (is (= [true false]
             (mapv :test? (paths/mark-tests [suite]
                                               [{:file "test/a_test.clj" :ns 'a-test}
                                                {:file "src/a.clj" :ns 'a}])))))))

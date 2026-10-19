(ns volley.e2e.watch-reruns-test
  "`volley --watch` as a real subprocess. Watch mode reruns only what a
  change affects."
  (:require
   [babashka.fs :as fs]
   [volley.e2e.support :as e2e]
   [volley.e2e.watch-support :as ws]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(deftest watch-reruns-only-what-a-change-affects
  (let [dir (e2e/copy-project "watch")
        w (ws/start! dir (ws/volley-command "--watch"))]
    (try
      (testing "the initial run covers everything"
        (let [text (ws/await-run! w 1)]
          (is (= #{"wx.alpha-test" "wx.beta-test" "wx.plain-test"} (ws/ran text)))
          (is (str/includes? text "PASS  Waiting for file changes"))
          (is (not (str/includes? text "press h")) "no key hint without a terminal")))
      (testing "a source change reruns only its dependents"
        (ws/write! dir "src/wx/alpha.clj" "(ns wx.alpha)\n\n(defn double-it [x] (+ x x))\n")
        (let [text (ws/await-run! w 2)]
          (is (= #{"wx.alpha-test"} (ws/ran text)))
          (is (str/includes? text "Change detected: src/wx/alpha.clj"))
          (is (str/includes? text "PASS"))))
      (testing "a broken test shows FAIL"
        (ws/write! dir "test/wx/alpha_test.clj"
                (str "(ns wx.alpha-test\n  (:require [clojure.test :refer [deftest is]]\n"
                     "            [wx.alpha :as alpha]))\n\n"
                     "(deftest doubles (is (= 5 (alpha/double-it 2))))\n"))
        (let [text (ws/await-run! w 3)]
          (is (= #{"wx.alpha-test"} (ws/ran text)))
          (is (str/includes? text "FAIL  1 failure  Waiting for file changes"))
          (is (str/includes? text "expected: 5"))))
      (testing "fixing it shows PASS again"
        (ws/write! dir "test/wx/alpha_test.clj"
                (str "(ns wx.alpha-test\n  (:require [clojure.test :refer [deftest is]]\n"
                     "            [wx.alpha :as alpha]))\n\n"
                     "(deftest doubles (is (= 4 (alpha/double-it 2))))\n"))
        (let [text (ws/await-run! w 4)]
          (is (= #{"wx.alpha-test"} (ws/ran text)))
          (is (str/includes? text "PASS  Waiting for file changes"))))
      (testing "a new test file runs by itself"
        (ws/write! dir "test/wx/gamma_test.clj"
                (str "(ns wx.gamma-test\n  (:require [clojure.test :refer [deftest is]]\n"
                     "            [wx.beta :as beta]))\n\n"
                     "(deftest quiet (is (= \"a!\" (beta/shout \"a\"))))\n"))
        (is (= #{"wx.gamma-test"} (ws/ran (ws/await-run! w 5)))))
      (testing "a change to a shared source reruns both its test namespaces"
        (ws/write! dir "src/wx/beta.clj" "(ns wx.beta)\n\n(defn shout [s] (str s \"!\"))\n;; edited\n")
        (is (= #{"wx.beta-test" "wx.gamma-test"} (ws/ran (ws/await-run! w 6)))))
      (testing "a deleted test file is handled without a crash"
        (fs/delete (fs/path dir "test/wx/gamma_test.clj"))
        (let [text (ws/await-run! w 7)]
          (is (str/includes? text "No test namespaces matched"))
          (is (str/includes? text "PASS"))))
      (testing "a tests.edn change reruns everything"
        (ws/write! dir "tests.edn" "#kaocha/v1 {:color? false}\n")
        (is (= #{"wx.alpha-test" "wx.beta-test" "wx.plain-test"} (ws/ran (ws/await-run! w 8)))))
      (finally
        (is (not= ::e2e/timeout (ws/stop! w)) "the process ends when killed")))))

(deftest watch-recomputes-the-classpath-when-bb-edn-changes
  (let [dir (e2e/copy-project "watch" {"extra/xtra/lib.clj" "(ns xtra.lib)\n\n(def answer 42)\n"
                                       "test/wx/extra_test.clj"
                                       (str "(ns wx.extra-test\n  (:require [clojure.test :refer [deftest is]]\n"
                                            "            [xtra.lib :as lib]))\n\n"
                                            "(deftest answers (is (= 42 lib/answer)))\n")})
        w (ws/start! dir (ws/volley-command "--watch"))]
    (try
      (testing "a namespace outside the classpath fails to load"
        (is (str/includes? (ws/await-run! w 1) "FAIL")))
      (testing "adding its directory to bb.edn reruns everything with the new classpath"
        (ws/write! dir "bb.edn" "{:paths [\"extra\"]}\n")
        (let [text (ws/await-run! w 2)]
          (is (str/includes? text "Change detected: bb.edn"))
          (is (contains? (ws/ran text) "wx.extra-test"))
          (is (str/includes? text "PASS  Waiting for file changes"))))
      (finally
        (is (not= ::e2e/timeout (ws/stop! w)) "the process ends when killed")))))

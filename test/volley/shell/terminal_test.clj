(ns volley.shell.terminal-test
  (:require
   [volley.shell.terminal :as term]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.string :as str]))

(deftest stty-size-parsing
  (is (= {:rows 50 :cols 132} (term/parse-stty-size "50 132\n")))
  (is (nil? (term/parse-stty-size "")))
  (is (nil? (term/parse-stty-size nil)))
  (is (nil? (term/parse-stty-size "0 0")))
  (is (nil? (term/parse-stty-size "stty: not a tty"))))

(deftest width-falls-back-from-stty-to-columns-to-80
  (is (= 132 (term/pick-width "50 132" "100")))
  (is (= 100 (term/pick-width "" "100")))
  (is (= 100 (term/pick-width nil " 100 ")))
  (is (= 80 (term/pick-width nil "wide")))
  (is (= 80 (term/pick-width nil "0")))
  (is (= 80 (term/pick-width nil nil))))

(deftest first-frame-does-not-move-the-cursor
  (is (= "\u001b[2Kdone\n\u001b[2Kone\n\u001b[2Ktwo\n\u001b[J"
         (term/frame-string 0 {:emit ["done"] :frame ["one" "two"]}))))

(deftest later-frames-move-up-over-the-previous-frame
  (is (= "\r\u001b[3A\u001b[2Kone\n\u001b[J"
         (term/frame-string 3 {:emit [] :frame ["one"]}))))

(defspec every-row-is-cleared-before-it-is-written 100
  (prop/for-all [h (gen/choose 0 20)
                 emit (gen/vector gen/string-alphanumeric 0 5)
                 frame (gen/vector gen/string-alphanumeric 0 5)]
    (let [s (term/frame-string h {:emit emit :frame frame})]
      (and (= (+ (count emit) (count frame)) (count (re-seq #"\u001b\[2K" s)))
           (= (+ (count emit) (count frame)) (count (re-seq #"\n" s)))
           (= (pos? h) (str/includes? s (str "\u001b[" h "A")))
           (= (count frame) (term/frame-height {:frame frame}))))))

(deftest restore-runs-after-success-and-after-failure
  (let [calls (atom 0)]
    (testing "normal exit"
      (is (= :result (term/with-restore! #(swap! calls inc) (fn [] :result))))
      (is (= 1 @calls)))
    (testing "exception"
      (is (thrown? clojure.lang.ExceptionInfo
                   (term/with-restore! #(swap! calls inc)
                     (fn [] (throw (ex-info "boom" {}))))))
      (is (= 2 @calls)))))

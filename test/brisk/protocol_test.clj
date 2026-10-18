(ns brisk.protocol-test
  (:require
   [brisk.gen.exec :as g]
   [brisk.protocol :as protocol]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

(defspec event-round-trips 200
  (prop/for-all [ev g/gen-event]
    (let [line (protocol/encode-event ev)]
      (and (not (re-find #"[\n\r]" line))
           (= {:kind :event :event ev} (protocol/decode-line line))))))

(defspec command-round-trips 200
  (prop/for-all [cmd g/gen-command]
    (let [line (protocol/encode-command cmd)]
      (and (not (re-find #"[\n\r]" line))
           (= {:kind :command :command cmd} (protocol/decode-command line))))))

(defspec decode-line-is-total 500
  (prop/for-all [line g/gen-line]
    (let [r (protocol/decode-line line)]
      (case (:kind r)
        :event (and (map? (:event r)) (keyword? (:event (:event r))))
        :output (and (= line (:text r))
                     (or (not (contains? r :protocol-error))
                         (str/starts-with? line protocol/event-prefix)))
        false))))

(defspec decode-command-is-total 300
  (prop/for-all [line g/gen-line]
    (contains? #{:command :invalid} (:kind (protocol/decode-command line)))))

(defspec encode-value-never-lies 300
  (prop/for-all [recipe g/gen-nasty]
    (let [{:keys [str edn] :as r} (protocol/encode-value (g/build-nasty recipe)
                                                         {:print-length 5 :print-level 4 :max-chars 2000})]
      (and (string? str)
           (<= (count str) 2003)
           (or (not (contains? r :edn))
               (= edn (edn/read-string str)))))))

(defspec small-edn-values-keep-their-data 300
  (prop/for-all [v g/gen-edn]
    (let [r (protocol/encode-value v {:print-length 1000 :print-level 100})]
      (and (= (pr-str v) (:str r))
           (contains? r :edn)
           (= v (:edn r))))))

(defspec error-encoding-shape 200
  (prop/for-all [tm g/gen-throwable-map]
    (let [err (protocol/encode-error tm)
          chain (take-while some? (iterate :cause err))
          frames (mapcat :trace chain)]
      (and (= (count (:via tm)) (count chain))
           (every? string? frames)
           (every? (every-pred (comp string? :class) #(contains? % :message)) chain)
           (not-any? #(re-find #"^(brisk\.|clojure\.test|babashka\.)" %) frames)
           (every? empty? (map :trace (butlast chain)))))))

(deftest stray-output-examples
  (testing "plain text is output"
    (is (= {:kind :output :text "hello"} (protocol/decode-line "hello"))))
  (testing "a malformed prefixed line stays visible as output, flagged"
    (let [r (protocol/decode-line "#brisk/event {:event")]
      (is (= :output (:kind r)))
      (is (= "#brisk/event {:event" (:text r)))
      (is (string? (:protocol-error r)))))
  (testing "prefixed but not an event map"
    (is (:protocol-error (protocol/decode-line "#brisk/event [1 2]")))
    (is (:protocol-error (protocol/decode-line "#brisk/event {:no-event 1}"))))
  (testing "nil and empty"
    (is (= {:kind :output :text ""} (protocol/decode-line nil)))
    (is (= {:kind :output :text ""} (protocol/decode-line "")))))

(deftest ->event-flattens-messages
  (let [ctx {:worker 3 :at 99 :stream :stdout}]
    (is (= {:event :ns/start :ns 'a :worker 3 :at 99}
           (protocol/->event {:kind :event :event {:event :ns/start :ns 'a}} ctx)))
    (is (= {:event :output :text "x" :stream :stdout :worker 3 :at 99}
           (protocol/->event {:kind :output :text "x"} ctx)))
    (is (= "boom" (:protocol-error
                   (protocol/->event {:kind :output :text "x" :protocol-error "boom"} ctx))))))

(deftest encode-value-examples
  (testing "plain data keeps :edn"
    (is (= {:str "{:a [1 2]}" :edn {:a [1 2]}} (protocol/encode-value {:a [1 2]}))))
  (testing "regexes, NaN, odd symbols and functions only get :str"
    (doseq [v [#"a+" ##NaN (symbol "a b") inc (g/->Point 1 2)]]
      (is (not (contains? (protocol/encode-value v) :edn)) (pr-str v))))
  (testing "infinite lazy seqs are cut and have no :edn"
    (let [r (protocol/encode-value (range) {:print-length 3})]
      (is (= "(0 1 2 ...)" (:str r)))
      (is (not (contains? r :edn)))))
  (testing "nesting is cut at :print-level"
    (is (= "[[[...]]]" (:str (protocol/encode-value [[[[[1]]]]] {:print-level 3})))))
  (testing "sorted maps keep their type when nothing is cut"
    (is (sorted? (:edn (protocol/encode-value (sorted-map 1 2))))))
  (testing "a throwing lazy seq does not throw"
    (is (string? (:str (protocol/encode-value (map #(/ 1 %) [1 0])))))))

(deftest encode-error-examples
  (let [e (ex-info "outer" {:k 1} (IllegalStateException. "inner"))
        err (protocol/encode-error (Throwable->map e))]
    (is (= "clojure.lang.ExceptionInfo" (:class err)))
    (is (= "outer" (:message err)))
    (is (= "{:k 1}" (:data err)))
    (is (= "java.lang.IllegalStateException" (:class (:cause err))))
    (is (nil? (:data (:cause err))))
    (is (every? string? (:trace (:cause err))))))

(defspec encode-check-keeps-seed-count-and-smallest-input 200
  (prop/for-all [seed gen/large-integer
                 n gen/nat
                 smallest (gen/vector gen/small-integer 0 3)]
    (let [m {:pass? false :result false :seed seed :num-tests n :failing-size 1
             :fail smallest :shrunk {:smallest smallest}}
          c (protocol/encode-check m)]
      (and (= seed (:seed c)) (= n (:num-tests c))
           (= smallest (get-in c [:smallest :edn]))
           (not (contains? c :result))))))

(defspec encode-check-is-nil-for-anything-else 200
  (prop/for-all [v (gen/one-of [gen/any-equatable
                                (gen/return {:pass? true :seed 1 :num-tests 1 :shrunk {}})])]
    (nil? (protocol/encode-check v))))

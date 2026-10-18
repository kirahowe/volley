(ns volley.protocol-test
  (:require
   [volley.gen.exec :as g]
   [volley.protocol :as protocol]
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
           (= [{:kind :event :event ev}] (protocol/decode-line line))))))

(def ^:private gen-print-bindings
  "Values a test may bind or `set!` the print vars to."
  (gen/let [length (gen/one-of [(gen/return nil) (gen/choose 0 3)])
            level (gen/one-of [(gen/return nil) (gen/choose 0 2)])
            dup gen/boolean
            meta? gen/boolean
            readably gen/boolean
            ns-maps gen/boolean]
    {#'*print-length* length #'*print-level* level #'*print-dup* dup
     #'*print-meta* meta? #'*print-readably* readably #'*print-namespace-maps* ns-maps}))

(defspec events-round-trip-whatever-the-print-vars 200
  (prop/for-all [ev g/gen-event
                 user gen-print-bindings]
    (let [line (with-bindings user
                 (with-bindings protocol/safe-print-bindings
                   (protocol/encode-event ev)))]
      (= [{:kind :event :event ev}] (protocol/decode-line line)))))

(defspec values-encode-the-same-whatever-the-print-vars 200
  (prop/for-all [v g/gen-edn
                 user gen-print-bindings]
    (= (with-bindings protocol/safe-print-bindings (protocol/encode-value v))
       (with-bindings user
         (with-bindings protocol/safe-print-bindings
           (protocol/encode-value v))))))

(defspec command-round-trips 200
  (prop/for-all [cmd g/gen-command]
    (let [line (protocol/encode-command cmd)]
      (and (not (re-find #"[\n\r]" line))
           (= {:kind :command :command cmd} (protocol/decode-command line))))))

(defspec decode-line-is-total 500
  (prop/for-all [line g/gen-line]
    (every? (fn [r]
              (case (:kind r)
                :event (and (map? (:event r)) (keyword? (:event (:event r))))
                :output (and (string? (:text r))
                             (or (not (contains? r :protocol-error))
                                 (and (= line (:text r))
                                      (str/starts-with? line protocol/event-prefix))))
                false))
            (protocol/decode-line line))))

(defn- read-lines
  "`text` split as `BufferedReader/readLine` splits it (the pool's
  `line-seq`): at `\\r\\n`, `\\r` or `\\n`, with no trailing empty line."
  [text]
  (let [lines (str/split text #"\r\n|\r|\n" -1)]
    (cond-> lines (= "" (peek lines)) pop)))

(def ^:private nonce "c0ffee42")

(def ^:private gen-foreign
  "What a child process that inherited the protocol pipe might write: any
  text, with or without newlines, partial prefixes, and the prefix of a
  worker that has no nonce (or another nonce) with a broken payload."
  (gen/one-of [gen/string
               gen/string-ascii
               (gen/elements ["\n" "\r" "\r\n" "" "#volley/ev" "#volley/event" "#volley/event:"
                              "stopping db..." "#volley/event {:oops" "#volley/event:c0ffee"
                              "#volley/event:deadbeef {:event :ns/end}" "{:event :ns/end}"])
               (gen/fmap #(str "#volley/event " %) gen/string)]))

(defspec interleaved-foreign-output-never-loses-an-event 500
  ;; A test's child process inherits the worker's stdout and writes to it
  ;; whenever it likes, newline or not; every event still arrives once.
  (prop/for-all [chunks (gen/vector (gen/one-of [(gen/fmap (fn [ev] [:event ev]) g/gen-event)
                                                 (gen/fmap (fn [s] [:foreign s]) gen-foreign)])
                                    0 12)]
    (let [prefix (protocol/prefix-for nonce)
          stream (apply str (map (fn [[kind x]]
                                   (if (= :event kind) (protocol/frame-event prefix x) x))
                                 chunks))
          messages (mapcat #(protocol/decode-line prefix %) (read-lines stream))
          foreign (keep (fn [[kind x]] (when (= :foreign kind) x)) chunks)]
      (and (= (keep (fn [[kind x]] (when (= :event kind) x)) chunks)
              (keep :event messages))
           (not-any? :protocol-error messages)
           (= (str/replace (apply str foreign) #"[\r\n]" "")
              (apply str (keep :text messages)))))))

(defspec decode-command-is-total 300
  (prop/for-all [line g/gen-line]
    (contains? #{:command :invalid} (:kind (protocol/decode-command line)))))

(defspec encode-value-never-lies 300
  (prop/for-all [recipe g/gen-nasty]
    (let [{s :str edn :edn :as r} (protocol/encode-value (g/build-nasty recipe)
                                                         {:print-length 5 :print-level 4 :max-chars 2000})]
      (and (string? s)
           (<= (count s) 2003)
           (or (not (contains? r :edn))
               (= edn (edn/read-string s)))))))

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
           (not-any? #(re-find #"^(volley\.|clojure\.test|babashka\.)" %) frames)
           (every? empty? (map :trace (butlast chain)))))))

(deftest stray-output-examples
  (testing "plain text is output"
    (is (= [{:kind :output :text "hello"}] (protocol/decode-line "hello"))))
  (testing "a malformed prefixed line stays visible as output, flagged"
    (let [[r & more] (protocol/decode-line "#volley/event {:event")]
      (is (nil? more))
      (is (= :output (:kind r)))
      (is (= "#volley/event {:event" (:text r)))
      (is (string? (:protocol-error r)))))
  (testing "prefixed but not an event map"
    (is (:protocol-error (first (protocol/decode-line "#volley/event [1 2]"))))
    (is (:protocol-error (first (protocol/decode-line "#volley/event {:no-event 1}")))))
  (testing "text before the prefix is a child's partial line, the event still counts (probe inheritio)"
    (is (= [{:kind :output :text "stopping db..."} {:kind :event :event {:event :ns/end}}]
           (protocol/decode-line "stopping db...#volley/event {:event :ns/end}")))
    (is (= [{:kind :event :event {:event :ns/end}} {:kind :output :text " tail"}]
           (protocol/decode-line "#volley/event {:event :ns/end} tail"))))
  (testing "a broken payload after foreign text is not a protocol error"
    (is (= [{:kind :output :text "x#volley/event {:event"}]
           (protocol/decode-line "x#volley/event {:event"))))
  (testing "with a nonce, the bare prefix a child prints is plain output (probe watchdog)"
    (let [prefix (protocol/prefix-for "abc123")]
      (is (= [{:kind :output :text "#volley/event {:oops"}]
             (protocol/decode-line prefix "#volley/event {:oops")))
      (is (= [{:kind :output :text "#volley/event:zzz {:event :ns/end}"}]
             (protocol/decode-line prefix "#volley/event:zzz {:event :ns/end}")))
      (is (= [{:kind :event :event {:event :ns/end}}]
             (protocol/decode-line prefix (protocol/encode-event prefix {:event :ns/end}))))))
  (testing "nil and empty lines give nothing: events are framed by newlines"
    (is (= [] (protocol/decode-line nil)))
    (is (= [] (protocol/decode-line "")))))

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

(def ^:private gen-frame
  (gen/hash-map :ns (gen/elements '[my.app my.app-test volley.shell.worker clojure.core clojure.test sci.impl.fns nil])
                :name (gen/elements '[parse run nil])
                :file (gen/elements ["/proj/src/my/app.clj" "clojure/core.clj" nil])
                :line (gen/choose 0 40)
                :sci/built-in (gen/frequency [[4 (gen/return false)] [1 (gen/return true)]])))

(defspec interpreter-trace-keeps-only-user-frames 300
  (prop/for-all [frames (gen/vector gen-frame 0 30)]
    (let [lines (protocol/interpreter-trace frames "/proj")]
      (and (every? #(str/starts-with? % "my.app") lines)
           (not-any? #(str/includes? % "/proj/") lines)
           (<= (count lines) (count frames))
           (every? (fn [[a b]] (not= a b)) (partition 2 1 lines))))))

(deftest interpreter-trace-of-a-call-three-deep
  ;; What sci.core/stacktrace gives for (outer 1) -> middle -> (inc (innermost x)) -> (/ x 0)
  (let [f "/proj/src/deep/core.clj"
        frames [{:ns 'clojure.core :name '/ :file "clojure/core.clj" :line 1022 :sci/built-in true}
                {:ns 'deep.core :name 'innermost :file f :line 4}
                {:ns 'deep.core :name 'innermost :file f :line 3}
                {:ns 'deep.core :file f :line 7}
                {:ns 'clojure.core :name 'inc :file "clojure/core.clj" :line 924 :sci/built-in true}
                {:ns 'deep.core :name 'middle :file f :line 7}
                {:ns 'deep.core :name 'middle :file f :line 6}
                {:ns 'deep.core :name 'outer :file f :line 10}
                {:ns 'deep.core-test :file "/proj/test/deep/core_test.clj" :line 6}
                {:ns 'volley.shell.worker :name 'run-vars! :file "volley/shell/worker.clj" :line 250}]]
    (is (= ["deep.core/innermost (src/deep/core.clj:4)"
            "deep.core/middle (src/deep/core.clj:7)"
            "deep.core/outer (src/deep/core.clj:10)"
            "deep.core-test (test/deep/core_test.clj:6)"]
           (protocol/interpreter-trace frames "/proj")))))

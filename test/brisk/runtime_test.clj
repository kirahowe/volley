(ns brisk.runtime-test
  (:require
   [brisk.config :as config]
   [brisk.gen.runtime :as g]
   [brisk.runtime :as runtime]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

(defn- alias-arg [as]
  (str "-A" (apply str (map #(str ":" (name %)) as))))

;; ---------------------------------------------------------------------------
;; Runtime and worker count

(defspec explicit-runtime-wins 100
  (prop/for-all [requested (gen/elements [:bb :jvm]) bb? gen/boolean]
    (= requested (runtime/resolve-runtime requested bb?))))

(defspec default-runtime-follows-the-parent 100
  (prop/for-all [bb? gen/boolean]
    (= (if bb? :bb :jvm) (runtime/resolve-runtime nil bb?))))

(defspec default-workers-are-bounded 200
  (prop/for-all [rt (gen/elements [:bb :jvm])
                 cpus (gen/one-of [(gen/return nil) (gen/choose 1 128)])]
    (let [cap (if (= rt :jvm) 4 8)
          n (runtime/default-workers rt cpus)]
      (and (<= 1 n cap)
           (or (nil? cpus) (<= n cpus))
           (= n (min (or cpus 4) cap))))))

;; ---------------------------------------------------------------------------
;; JVM options

(defspec forwarded-args-keep-input-order 200
  (prop/for-all [args g/input-args]
    (let [out (runtime/forward-jvm-args args)]
      (= out (filterv (set out) args)))))

(defspec forwarding-is-idempotent 200
  (prop/for-all [args g/input-args]
    (let [once (runtime/forward-jvm-args args)]
      (= once (runtime/forward-jvm-args once)))))

(defspec safe-args-are-all-forwarded 200
  (prop/for-all [args (gen/vector g/safe-arg 0 10)]
    (= args (runtime/forward-jvm-args args))))

(defspec dropped-args-and-class-path-options-never-survive 200
  (prop/for-all [args g/input-args]
    (let [out (runtime/forward-jvm-args args)]
      (and (not-any? #{"-cp" "-classpath" "--class-path" "a:b:c"} out)
           (not-any? #(re-find #"jdwp|jmxremote|clojure\.server|-Xdebug|^-Xlog|FlightRecording|ErrorFile|HeapDumpPath|java\.class\.path" %)
                     out)))))

(defspec every-safe-arg-in-the-input-is-forwarded 200
  (prop/for-all [args g/input-args
                 safe (gen/vector g/safe-arg 1 5)]
    (let [mixed (vec (concat args safe))]
      (= safe (take-last (count safe) (runtime/forward-jvm-args mixed))))))

;; ---------------------------------------------------------------------------
;; Aliases and the classpath cache

(defspec alias-strings-round-trip 200
  (prop/for-all [as g/aliases]
    (= as (runtime/parse-aliases (apply str (map #(str ":" (name %)) as))))))

(defspec explicit-aliases-win 100
  (prop/for-all [as (gen/such-that seq g/aliases) text g/deps-text]
    (= as (runtime/choose-aliases as text))))

(defspec test-alias-is-the-default-when-defined 200
  (prop/for-all [text g/deps-text]
    (let [defined? (contains? (:aliases (edn/read-string text)) :test)]
      (= (if defined? [:test] []) (runtime/choose-aliases nil text)))))

(deftest default-aliases-for-odd-deps
  (is (= [] (runtime/choose-aliases nil nil)))
  (is (= [] (runtime/choose-aliases [] "{:paths [")))
  (is (= [] (runtime/choose-aliases nil "[1 2]")))
  (is (= [:test] (runtime/choose-aliases nil "{:aliases {:test {}}}")))
  (is (= [:dev] (runtime/choose-aliases [:dev] "{:aliases {:test {}}}"))))

(defspec classpath-key-is-deterministic-and-short 200
  (prop/for-all [text gen/string as g/aliases]
    (let [k (runtime/classpath-key text as)]
      (and (= k (runtime/classpath-key text as))
           (boolean (re-matches #"[0-9a-f]{16}" k))))))

(defspec classpath-key-depends-on-text-and-aliases 200
  (prop/for-all [a gen/string b gen/string as g/aliases bs g/aliases]
    (or (and (= a b) (= as bs))
        (not= (runtime/classpath-key a as) (runtime/classpath-key b bs)))))

(deftest classpath-key-treats-missing-deps-as-empty
  (is (= (runtime/classpath-key nil [:test]) (runtime/classpath-key "" [:test])))
  (is (not= (runtime/classpath-key "{}" [:a :b]) (runtime/classpath-key "{}" [:b :a]))
      "alias order is classpath order"))

(deftest cache-file-is-under-dot-brisk
  (is (= ".brisk/classpath-abc.txt" (runtime/cache-file "abc"))))

(defspec spath-command-names-every-alias 100
  (prop/for-all [as g/aliases]
    (let [cmd (runtime/spath-command as)]
      (and (= ["clojure" "-Spath"] (take 2 cmd))
           (if (seq as)
             (= (alias-arg as) (nth cmd 2))
             (= 2 (count cmd)))))))

(defspec alias-jvm-opts-follow-alias-order 200
  (prop/for-all [text g/deps-text]
    (let [defined (:aliases (edn/read-string text))
          as (vec (keys defined))]
      (= (vec (mapcat #(:jvm-opts (get defined %)) as))
         (runtime/alias-jvm-opts text as)))))

(deftest alias-jvm-opts-ignores-bad-input
  (is (= [] (runtime/alias-jvm-opts nil [:test])))
  (is (= [] (runtime/alias-jvm-opts "{:aliases {:test {}}}" [:test :missing])))
  (is (= ["-Xmx1g"] (runtime/alias-jvm-opts "{:aliases {:test {:jvm-opts [\"-Xmx1g\"]}}}" [:test]))))

(defspec classpath-joins-without-duplicates 200
  (prop/for-all [entries (gen/vector gen/string-alphanumeric 0 8)]
    (let [cp (runtime/join-classpath entries ":")
          back (runtime/split-classpath cp ":")]
      (and (= back (vec (distinct (remove str/blank? entries))))
           (= cp (runtime/join-classpath back ":"))))))

;; ---------------------------------------------------------------------------
;; Command

(defspec jvm-command-shape 100
  (prop/for-all [args g/input-args]
    (let [fwd (runtime/forward-jvm-args args)
          cmd (runtime/jvm-worker-command {:java "/j/bin/java" :jvm-args fwd :classpath "a:b"})]
      (and (= "/j/bin/java" (first cmd))
           (= runtime/default-jvm-args (subvec cmd 1 (inc (count runtime/default-jvm-args))))
           (= fwd (subvec cmd (inc (count runtime/default-jvm-args))
                          (+ (inc (count runtime/default-jvm-args)) (count fwd))))
           (= ["-cp" "a:b" "clojure.main" "-m" "brisk.shell.worker"]
              (subvec cmd (+ (inc (count runtime/default-jvm-args)) (count fwd))))))))

;; ---------------------------------------------------------------------------
;; Config

(deftest config-picks-runtime-and-worker-count
  (testing "a babashka parent defaults to bb workers, capped at 8"
    (let [c (config/normalize nil {} {:cpus 16 :bb? true})]
      (is (= [:bb 8] ((juxt :runtime :workers) c)))))
  (testing "a JVM parent defaults to JVM workers, capped at 4"
    (let [c (config/normalize nil {} {:cpus 16 :bb? false})]
      (is (= [:jvm 4] ((juxt :runtime :workers) c)))))
  (testing "--runtime jvm from babashka uses the JVM cap"
    (let [c (config/normalize nil {:runtime :jvm} {:cpus 16 :bb? true})]
      (is (= [:jvm 4] ((juxt :runtime :workers) c)))))
  (testing "--runtime bb from the JVM uses the babashka cap"
    (let [c (config/normalize nil {:runtime :bb} {:cpus 16 :bb? false})]
      (is (= [:bb 8] ((juxt :runtime :workers) c)))))
  (testing "--workers always wins"
    (is (= 12 (:workers (config/normalize nil {:workers 12 :runtime :jvm} {:cpus 2 :bb? true})))))
  (testing "isolating JVM workers warns"
    (is (some #(re-find #"slow" %)
              (:warnings (config/normalize nil {:runtime :jvm :isolate true} {:bb? true}))))
    (is (not-any? #(re-find #"slow" %)
                  (:warnings (config/normalize nil {:isolate true} {:bb? true}))))))

(deftest invocation-is-what-the-user-ran
  (is (= "bin/brisk" (runtime/invocation {:command "bin/brisk" :bb? true :task "test"})))
  (is (= "bb test" (runtime/invocation {:bb? true :task "test"})))
  (is (= "bb -m brisk.main" (runtime/invocation {:bb? true})))
  (is (= "clojure -M:test -m brisk.main" (runtime/invocation {:aliases [:test] :main-opts nil})))
  (is (= "clojure -M:test:brisk" (runtime/invocation {:aliases [:test :brisk] :main-opts ["-m" "brisk.main"]})))
  (is (= "clojure -M -m brisk.main" (runtime/invocation {}))))

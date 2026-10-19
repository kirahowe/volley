(ns volley.runtime-test
  (:require
   [volley.config :as config]
   [volley.gen.runtime :as g]
   [volley.runtime :as runtime]
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

(defspec classpath-key-depends-on-every-other-deps-file 200
  (prop/for-all [text gen/string as g/aliases files g/deps-files
                 path gen/string-alphanumeric changed gen/string]
    (let [k (runtime/classpath-key text as files)]
      (and (= k (runtime/classpath-key text as (into {} (reverse (seq files)))))
           (or (= changed (get files path))
               (not= k (runtime/classpath-key text as (assoc files path changed))))))))

(deftest classpath-key-without-other-files
  (is (= (runtime/classpath-key "{}" [:test]) (runtime/classpath-key "{}" [:test] {})))
  (is (not= (runtime/classpath-key "{}" [:test] {"a/deps.edn" nil})
            (runtime/classpath-key "{}" [:test] {}))
      "a missing file is an input too: creating it changes the key"))

(defspec local-roots-finds-every-local-dep 200
  (prop/for-all [roots (gen/vector (gen/such-that seq gen/string-alphanumeric) 0 5)
                 alias-key (gen/elements [nil :extra-deps :override-deps :default-deps :replace-deps :deps])]
    (let [deps (into {} (map-indexed (fn [i r] [(symbol "lib" (str "l" i)) {:local/root r}])) roots)
          text (pr-str (if alias-key
                         {:aliases {:test {alias-key deps}}}
                         {:deps (assoc deps 'org/m {:mvn/version "1"})}))]
      (= (vec (sort (distinct roots))) (runtime/local-roots text)))))

(deftest local-roots-ignores-bad-input
  (is (= [] (runtime/local-roots nil)))
  (is (= [] (runtime/local-roots "{:deps")))
  (is (= [] (runtime/local-roots "{:deps {a/b {:local/root 1}} :aliases []}"))))

(defn- join-abs [dir rel]
  (if (str/starts-with? rel "/") rel (str dir "/" rel)))

(defn- reachable [edges from]
  (loop [seen #{} todo (vec (get edges from))]
    (if-let [d (first todo)]
      (recur (conj seen d) (into (subvec todo 1) (when-not (seen d) (get edges d))))
      seen)))

(defspec local-deps-files-follow-local-roots-through-cycles 200
  (prop/for-all [edges g/dep-graph]
    (let [text-of (fn [dir] (pr-str {:deps (into {} (map (fn [d] [(symbol "x" d) {:local/root (str "/" d)}]))
                                                 (get edges dir))}))
          files (into {} (map (fn [d] [(str "/" d "/deps.edn") (text-of d)])) (keys edges))
          found (runtime/local-deps-files (get files "/a/deps.edn") "/a" {:read files :join join-abs})]
      (and (= (set (map #(str "/" % "/deps.edn") (reachable edges "a"))) (set (keys found)))
           (every? (fn [[f t]] (= t (get files f))) found)))))

(deftest local-deps-files-stop-at-cycles-and-self-loops
  (let [files {"/a/deps.edn" "{:deps {b/b {:local/root \"/b\"}}}"
               "/b/deps.edn" "{:deps {a/a {:local/root \"/a\"} b/b {:local/root \"/b\"}}}"}]
    (is (= files (runtime/local-deps-files (get files "/a/deps.edn") "/a"
                                           {:read files :join join-abs})))
    (is (= {"/c/deps.edn" "{:deps {c/c {:local/root \"/c\"}}}"}
           (runtime/local-deps-files "{:deps {c/c {:local/root \"/c\"}}}" "/x"
                                     {:read {"/c/deps.edn" "{:deps {c/c {:local/root \"/c\"}}}"}
                                      :join join-abs})))))

(deftest local-deps-files-resolve-relative-roots-from-their-own-directory
  (let [files {"/p/../a/deps.edn" "{:deps {b/b {:local/root \"../b\"}}}"
               "/p/../a/../b/deps.edn" "{}"}]
    (is (= files (runtime/local-deps-files "{:deps {a/a {:local/root \"../a\"}}}" "/p"
                                           {:read files :join join-abs})))))

(deftest user-deps-file-follows-the-clojure-cli
  (is (= "/cfg/deps.edn" (runtime/user-deps-file {:clj-config "/cfg" :xdg-config-home "/x" :home "/h"})))
  (is (= "/x/clojure/deps.edn" (runtime/user-deps-file {:xdg-config-home "/x" :home "/h"})))
  (is (= "/h/.clojure/deps.edn" (runtime/user-deps-file {:clj-config "" :home "/h"})))
  (is (nil? (runtime/user-deps-file {}))))

(deftest parent-classpath-command-asks-the-parents-own-tool
  (is (= "bb" (first (runtime/parent-classpath-command {:bb? true}))))
  (is (= ["clojure" "-Spath" "-A:test:dev"]
         (runtime/parent-classpath-command {:bb? false :aliases [:test :dev]})))
  (is (= ["clojure" "-Spath"] (runtime/parent-classpath-command {:bb? false}))))

(defspec refreshed-classpath-puts-fresh-entries-first 200
  (prop/for-all [fresh (gen/vector (gen/elements ["a" "b" "c" "d"]) 0 4)
                 old (gen/vector (gen/elements ["c" "d" "e" "f"]) 0 4)]
    (let [entries (runtime/split-classpath (runtime/refreshed-classpath (str/join ":" fresh) (str/join ":" old) ":") ":")]
      (and (= (vec (distinct (concat fresh old))) entries)
           (= (distinct fresh) (take (count (distinct fresh)) entries))))))

(deftest cache-file-is-under-dot-volley
  (is (= ".volley/classpath-abc.txt" (runtime/cache-file "abc"))))

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
           (= ["-cp" "a:b" "clojure.main" "-m" "volley.shell.worker"]
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
  (is (= "bin/volley" (runtime/invocation {:command "bin/volley" :bb? true :task "test"})))
  (is (= "bb test" (runtime/invocation {:bb? true :task "test"})))
  (is (= "bb -m volley.main" (runtime/invocation {:bb? true})))
  (is (= "clojure -M:test -m volley.main" (runtime/invocation {:aliases [:test] :main-opts nil})))
  (is (= "clojure -M:test:volley" (runtime/invocation {:aliases [:test :volley] :main-opts ["-m" "volley.main"]})))
  (is (= "clojure -M -m volley.main" (runtime/invocation {}))))

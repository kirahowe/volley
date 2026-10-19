(ns volley.gen.runtime
  "Generators for `volley.runtime`: JVM launch arguments, aliases and
  deps.edn text."
  (:require
   [clojure.test.check.generators :as gen]))

(def safe-arg
  "JVM arguments volley forwards unchanged."
  (gen/one-of [(gen/fmap #(str "-Xmx" % "m") (gen/choose 64 4096))
               (gen/fmap #(str "-Dfoo.bar=" %) gen/string-alphanumeric)
               (gen/elements ["-XX:-OmitStackTraceInFastThrow" "-XX:+UseSerialGC"
                              "--add-opens=java.base/java.lang=ALL-UNNAMED"
                              "-Dclojure.basis=.cpcache/1.basis"
                              "-Dfile.encoding=UTF-8" "-javaagent:/tmp/agent.jar"])]))

(def dropped-arg
  "JVM arguments volley must not forward."
  (gen/elements ["-agentlib:jdwp=transport=dt_socket,server=y,address=5005"
                 "-Xrunjdwp:transport=dt_socket,address=5005" "-Xdebug"
                 "-Dcom.sun.management.jmxremote.port=9010"
                 "-Dcom.sun.management.jmxremote"
                 "-Dclojure.server.repl={:port 5555 :accept clojure.core.server/repl}"
                 "-Djava.class.path=/x/y" "-Xlog:gc*:file=gc.log"
                 "-XX:StartFlightRecording=duration=10s"
                 "-XX:ErrorFile=/tmp/hs_err.log" "-XX:HeapDumpPath=/tmp/dump"]))

(def class-path-pair
  "A class path option and its value, as separate arguments."
  (gen/fmap (fn [flag] [flag "a:b:c"])
            (gen/elements ["-cp" "-classpath" "--class-path"])))

(def input-args
  "A launch argument list with safe args, dropped args and class path
  pairs mixed."
  (gen/fmap #(vec (apply concat %))
            (gen/vector (gen/frequency [[5 (gen/fmap vector safe-arg)]
                                        [3 (gen/fmap vector dropped-arg)]
                                        [1 class-path-pair]])
                        0 12)))

(def alias-kw
  "A deps.edn alias."
  (gen/fmap keyword (gen/such-that seq gen/string-alphanumeric 20)))

(def aliases
  "Up to four aliases."
  (gen/vector alias-kw 0 4))

(def deps-text
  "deps.edn text defining some aliases, each with optional `:jvm-opts`."
  (gen/fmap (fn [m] (pr-str {:paths ["src"] :aliases m}))
            (gen/map alias-kw
                     (gen/fmap (fn [opts] (cond-> {:extra-paths ["test"]}
                                            (seq opts) (assoc :jvm-opts opts)))
                               (gen/vector safe-arg 0 3))
                     {:max-elements 4})))

(def deps-files
  "The other deps.edn files a classpath depends on, `{path text-or-nil}`."
  (gen/map gen/string-alphanumeric (gen/one-of [(gen/return nil) gen/string]) {:max-elements 4}))

(def dep-graph
  "Directories `a` to `d`, each listing the directories its deps.edn points
  at with `:local/root` (`missing` has no deps.edn). `a`, where traversal
  starts, always has an entry."
  (let [roots (gen/vector (gen/elements ["a" "b" "c" "d" "missing"]) 0 3)]
    (gen/fmap (fn [[a m]] (assoc m "a" a))
              (gen/tuple (gen/not-empty roots) (gen/map (gen/elements ["b" "c" "d"]) roots)))))

(def wall-ms
  "A run's wall time in milliseconds, on both sides of
  `volley.runtime/in-process-limit-ms`."
  (gen/one-of [(gen/choose 0 12000) (gen/choose 0 120000)]))

(def history
  "What `.volley/history.edn` may hold: the recent in-process run times
  and worker-process run times, newest last, and the short worker-process
  runs since the last in-process one, each possibly absent."
  (gen/fmap (fn [[ip wk streak]]
              (cond-> {}
                (seq ip) (assoc :in-process-ms ip)
                (seq wk) (assoc :workers-ms wk)
                streak (assoc :workers-streak streak)))
            (gen/tuple (gen/vector wall-ms 0 3) (gen/vector wall-ms 0 3)
                       (gen/one-of [(gen/return nil) (gen/choose 0 8)]))))

(def serial-ms
  "A plan's serial time by the timings on record, or nil when unknown."
  (gen/one-of [(gen/return nil) wall-ms]))

(def worker-plan-input
  "Input for `volley.runtime/worker-plan`."
  (gen/hash-map :workers (gen/choose 1 16)
                :workers-set? gen/boolean
                :in-process-ok? gen/boolean
                :history history
                :serial-ms serial-ms))

(def run-mode
  "What `volley.runtime/heuristic-run?` reads about a run."
  (gen/hash-map :parent (gen/elements [:bb :jvm])
                :runtime (gen/elements [:bb :jvm])
                :workers-set? gen/boolean
                :watch? gen/boolean
                :isolate? gen/boolean))

(def ns-sym
  "A namespace symbol from a small pool, so plans and hooks share some."
  (gen/elements '[a.core-test a.util-test b.hooks b.reporter c.setup]))

(def plan
  "The parts of a `volley.select/plan` that `in-process-ok?` reads."
  (gen/hash-map :suites (gen/vector (gen/hash-map :namespaces (gen/vector (gen/fmap #(hash-map :ns %) ns-sym) 0 3))
                                    0 2)
                :preload (gen/vector (gen/fmap #(hash-map :ns %) ns-sym) 0 2)))

(def configure
  "A `:configure` message naming hook, setup and reporter vars."
  (let [var-sym (gen/fmap (fn [[n v]] (symbol (str n) (name v))) (gen/tuple ns-sym (gen/elements '[f g])))]
    (gen/hash-map :hooks (gen/map (gen/elements [:pre-run :post-load :wrap-run]) (gen/vector var-sym 0 2)
                                  {:max-elements 2})
                  :worker-setup (gen/vector var-sym 0 2)
                  :reporters (gen/vector var-sym 0 2))))

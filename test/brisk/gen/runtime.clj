(ns brisk.gen.runtime
  "Generators for `brisk.runtime`: JVM launch arguments, aliases and
  deps.edn text."
  (:require
   [clojure.test.check.generators :as gen]))

(def safe-arg
  "JVM arguments brisk forwards unchanged."
  (gen/one-of [(gen/fmap #(str "-Xmx" % "m") (gen/choose 64 4096))
               (gen/fmap #(str "-Dfoo.bar=" %) gen/string-alphanumeric)
               (gen/elements ["-XX:-OmitStackTraceInFastThrow" "-XX:+UseSerialGC"
                              "--add-opens=java.base/java.lang=ALL-UNNAMED"
                              "-Dclojure.basis=.cpcache/1.basis"
                              "-Dfile.encoding=UTF-8" "-javaagent:/tmp/agent.jar"])]))

(def dropped-arg
  "JVM arguments brisk must not forward."
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

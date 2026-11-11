(ns volley.shell.jvm
  "JVM-only interop. Babashka ships neither `java.lang.management` nor
  Clojure's class loaders, so this namespace is loaded (through
  `requiring-resolve`) only when the parent runs on the JVM."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (clojure.lang DynamicClassLoader RT)
   (java.lang.management ManagementFactory)))

(set! *warn-on-reflection* true)

(defn input-arguments
  "This JVM's launch options (`-Xmx...`, `-D...`, `--add-opens ...`)."
  []
  (vec (.getInputArguments (ManagementFactory/getRuntimeMXBean))))

(defn add-classpath!
  "Put the directories and jars `paths` on this JVM's classpath, as
  `kaocha.classpath/add-classpath` does when Kaocha loads a suite. The
  root of `clojure.lang.Compiler/LOADER` gets a `DynamicClassLoader` when
  it has no value, and each path is added to every `DynamicClassLoader`
  from the current loader up, so `require` and `clojure.java.io/resource`
  find what is under them on any thread. Returns the current loader."
  ^ClassLoader [paths]
  (when-not (bound? clojure.lang.Compiler/LOADER)
    (.bindRoot clojure.lang.Compiler/LOADER (DynamicClassLoader. (RT/baseLoader))))
  (let [^ClassLoader tip @clojure.lang.Compiler/LOADER
        urls (mapv #(.toURL (.toURI (.getCanonicalFile (io/file %)))) paths)]
    (doseq [cl (take-while some? (iterate #(.getParent ^ClassLoader %) tip))
            :when (instance? DynamicClassLoader cl)
            url urls]
      (.addURL ^DynamicClassLoader cl url))
    tip))

(defn- runtime-exit? [^StackTraceElement frame]
  (and (= "java.lang.Runtime" (.getClassName frame)) (= "exit" (.getMethodName frame))))

(defn- frame-text [^StackTraceElement frame]
  (str (.getClassName frame) "." (.getMethodName frame)
       " (" (.getFileName frame) ":" (.getLineNumber frame) ")"))

(defn exit-caller
  "For a shutdown hook: when this JVM is shutting down because a thread
  other than `own` called `System/exit`, the stack frame that called it as
  text, or \"an unknown caller\"; else nil. `System/exit` goes through
  `Runtime.exit`, and its thread waits there while the hooks run. A
  shutdown that a signal started has no such thread."
  [^Thread own]
  (some (fn [^java.util.Map$Entry entry]
          (let [[exit & below] (drop-while (complement runtime-exit?) (.getValue entry))]
            (when (and exit (not (identical? own (.getKey entry))))
              (or (some->> below
                           (remove (fn [^StackTraceElement frame]
                                     (str/starts-with? (.getClassName frame) "java.lang.")))
                           first
                           frame-text)
                  "an unknown caller"))))
        (Thread/getAllStackTraces)))

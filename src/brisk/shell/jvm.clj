(ns brisk.shell.jvm
  "JVM-only interop. Babashka does not ship `java.lang.management`, so this
  namespace is loaded (through `requiring-resolve`) only when the parent
  runs on the JVM."
  (:import
   (java.lang.management ManagementFactory)))

(set! *warn-on-reflection* true)

(defn input-arguments
  "This JVM's launch options (`-Xmx...`, `-D...`, `--add-opens ...`)."
  []
  (vec (.getInputArguments (ManagementFactory/getRuntimeMXBean))))

(ns gs.hooks
  "A post-run hook that leaves one marker file per worker, holding how many
  vars of each namespace that worker ran.")

(def ran
  "`{ns-sym n}`: the vars this worker has started, bumped by the tests."
  (atom {}))

(defn bump!
  "Count one var of `ns-sym`, and note on disk that it has begun."
  [ns-sym]
  (.mkdirs (java.io.File. "markers"))
  (spit (str "markers/started-" ns-sym) "")
  (swap! ran update ns-sym (fnil inc 0)))

(defn mark [result]
  (.mkdirs (java.io.File. "markers"))
  (spit (str "markers/post-run-" (.pid (java.lang.ProcessHandle/current)) ".edn") (pr-str @ran))
  result)

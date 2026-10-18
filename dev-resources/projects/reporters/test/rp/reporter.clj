(ns rp.reporter
  "A project's own reporter, as `--reporter rp.reporter/report` names it.")

(defn report
  "Print one line per test var that ends."
  [m]
  (when (= :end-test-var (:type m))
    (println "REPORTED" (:name (meta (:var m))))))

(def both
  "A reporter var holding a vector of reporters, as Kaocha's own are."
  ['rp.reporter/report])

(ns brisk.test-bootstrap
  "Runs brisk's own tests with plain clojure.test until brisk can run itself.
  Pass namespace names to run only those."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :as t]))

(defn- file->ns [path]
  (-> (str (fs/relativize "test" path))
      (str/replace #"\.clj[cs]?$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")
      symbol))

(defn- test-namespaces []
  (->> (fs/glob "test" "**_test.clj{,c}")
       (map file->ns)
       sort))

(defn run-tests!
  "Requires and runs the given namespaces, or every *_test namespace."
  [args]
  (let [nses (if (seq args) (map symbol args) (test-namespaces))]
    (when (seq nses) (apply require nses))
    (let [{:keys [fail error]} (apply t/run-tests nses)]
      (System/exit (if (zero? (+ fail error)) 0 1)))))

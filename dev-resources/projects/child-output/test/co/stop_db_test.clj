(ns co.stop-db-test
  "A :once fixture whose child process writes to the inherited stdout
  without a trailing newline, the last thing before :ns/end."
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once (fn [f]
                      (f)
                      (-> (ProcessBuilder. ["printf" "stopping db..."]) (.inheritIO) (.start) (.waitFor))))

(deftest last-one (is true))

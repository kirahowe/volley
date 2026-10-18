(ns sp.crash-test
  "Leaves a background process running, then exits the worker."
  (:require [clojure.test :refer [deftest]]))

(deftest crashes-with-a-daemon
  (-> (ProcessBuilder. ["sh" "-c" "sleep 20 &"]) (.inheritIO) (.start) (.waitFor))
  (System/exit 1))

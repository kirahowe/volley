(ns sp.daemon-test
  "Leaves a background process running that inherited the worker's stdout
  and stderr, as a test starting a server would."
  (:require [clojure.test :refer [deftest is]]))

(deftest leaves-a-daemon
  (-> (ProcessBuilder. ["sh" "-c" "sleep 20 &"]) (.inheritIO) (.start) (.waitFor))
  (is true))

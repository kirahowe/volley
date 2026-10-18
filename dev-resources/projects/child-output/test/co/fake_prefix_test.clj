(ns co.fake-prefix-test
  "A child process logs a line that starts with the protocol prefix."
  (:require [clojure.test :refer [deftest is]]))

(deftest logs-the-prefix
  (-> (ProcessBuilder. ["sh" "-c" "echo '#volley/event {:oops'"]) (.inheritIO) (.start) (.waitFor))
  (is true))

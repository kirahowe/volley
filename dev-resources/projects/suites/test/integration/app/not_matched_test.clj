(ns app.not-matched-test
  "Ends in -test, but the integration suite only takes -itest$."
  (:require [clojure.test :refer [deftest is]]))

(deftest ignored (is false))

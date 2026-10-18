(ns boom.exit-test
  (:require [clojure.test :refer [deftest is]]))

(deftest before-the-exit (is true))

(deftest exits-the-process
  (println "about to exit")
  (System/exit 3))

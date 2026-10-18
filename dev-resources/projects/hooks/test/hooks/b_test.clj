(ns hooks.b-test
  (:require [clojure.test :refer [deftest is]]
            [hooks.probe :as probe]))

(deftest also-set-up (Thread/sleep 300) (is (true? @probe/setup-done)))

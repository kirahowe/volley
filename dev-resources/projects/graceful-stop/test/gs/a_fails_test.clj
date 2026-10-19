(ns gs.a-fails-test
  (:require [clojure.test :refer [deftest is]]
            [gs.hooks :as hooks]))

;; Give the other namespaces time to start before the failure arrives.
(deftest fails (hooks/bump! 'gs.a-fails-test) (Thread/sleep 1500) (is (= 1 2)))

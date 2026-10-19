(ns gs.hung-test
  (:require [clojure.test :refer [deftest is]]
            [gs.hooks :as hooks]))

(deftest never-returns (hooks/bump! 'gs.hung-test) (Thread/sleep 600000) (is true))

(ns wx.alpha-test
  (:require [clojure.test :refer [deftest is]]
            [wx.alpha :as alpha]))

(deftest doubles (is (= 4 (alpha/double-it 2))))

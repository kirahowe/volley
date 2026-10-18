(ns wx.beta-test
  (:require [clojure.test :refer [deftest is]]
            [wx.beta :as beta]))

(deftest shouts (is (= "hi!" (beta/shout "hi"))))

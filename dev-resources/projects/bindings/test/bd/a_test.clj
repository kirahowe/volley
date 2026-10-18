(ns bd.a-test
  (:require [bd.config :as config]
            [clojure.test :refer [deftest is]]))

(deftest sees-the-binding (is (= :configured config/*mode*)))

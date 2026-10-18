(ns app.db-itest
  (:require [clojure.test :refer [deftest is]]))

(deftest round-trip (is (= {:id 1} (select-keys {:id 1 :x 2} [:id]))))

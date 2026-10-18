(ns shop.fixture-error-test
  (:require [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :once (fn [_] (throw (ex-info "database not reachable" {}))))

(deftest needs-db (is true))

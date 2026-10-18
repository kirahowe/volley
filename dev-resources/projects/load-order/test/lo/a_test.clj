(ns lo.a-test
  "Registers an assertion that lo.b-test uses without requiring this
  namespace, which works only when everything is loaded first."
  (:require [clojure.test :as t :refer [deftest is]]))

(defmethod t/assert-expr 'roughly= [msg [_ a b]]
  `(let [a# ~a b# ~b]
     (t/do-report {:type (if (< (Math/abs (- a# b#)) 0.01) :pass :fail)
                   :message ~msg :expected '~a :actual b#})))

(deftest a (is (roughly= 1.0 1.001)))

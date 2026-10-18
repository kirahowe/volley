(ns shop.cart-test
  (:require [clojure.test :refer [deftest is testing]]
            [shop.cart :as cart]))

(deftest totals
  (is (= 30 (cart/total [{:price 10} {:price 20}]))))

(deftest add-item-shape
  (testing "adding to an empty cart"
    (is (= {:items [{:sku 1}] :count 1}
           (cart/add-item {} {:sku 1})))))

(deftest prints-then-fails
  (println "computing the discount")
  (is (= 5 (cart/total [{:price 4}]))))

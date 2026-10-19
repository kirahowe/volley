(ns volley.order-test
  (:require
   [volley.gen.order :as g]
   [volley.order :as order]
   [clojure.test :refer [deftest is]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

(defspec shuffled-order-is-a-permutation 200
  (prop/for-all [seed g/seed ns g/namespace-name names g/var-names]
    (let [out (order/shuffle-names seed ns names)]
      (and (= (count names) (count out))
           (= (set names) (set out))))))

(defspec order-is-stable-for-a-seed-whatever-the-input-order 200
  (prop/for-all [seed g/seed ns g/namespace-name names g/var-names]
    (= (order/shuffle-names seed ns names)
       (order/shuffle-names seed ns (reverse names))
       (order/shuffle-names seed ns (sort names)))))

(defspec a-subset-keeps-its-relative-order 200
  ;; Adding or filtering out a test does not reorder the others.
  (prop/for-all [seed g/seed ns g/namespace-name names g/var-names keep? (gen/vector gen/boolean 30)]
    (let [subset (keep-indexed (fn [i n] (when (nth keep? i) n)) names)
          kept (set subset)]
      (= (filter kept (order/shuffle-names seed ns names))
         (order/shuffle-names seed ns subset)))))

(deftest seeds-and-namespaces-change-the-order
  ;; kaocha.plugin.randomize/rng-sort: different seeds give different orders
  (let [names (mapv #(symbol (str "t" %)) (range 20))
        orders (fn [f] (set (map f (range 20))))]
    (is (< 1 (count (orders #(order/shuffle-names % 'p.a-test names)))))
    (is (< 1 (count (orders #(order/shuffle-names 7 (symbol (str "p.n" % "-test")) names)))))
    (is (not= names (order/shuffle-names 7 'p.a-test names)))))

(deftest a-known-seed-gives-a-known-order
  ;; Pins the mixing function: bb and the JVM workers must agree on it.
  (is (= '[a e g h b f c d] (order/shuffle-names 42 'p.a-test '[a b c d e f g h]))))

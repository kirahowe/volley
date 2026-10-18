(ns shop.cart)

(defn total [items] (reduce + (map :price items)))
(defn add-item [cart item] (update cart :items (fnil conj []) item))

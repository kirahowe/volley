(ns deep.core)

(defn innermost [x]
  (/ x 0))

(defn middle [x]
  (inc (innermost x)))

(defn outer [x]
  (middle (* 2 x)))

(defn explode [m]
  (throw (ex-info "kaboom" m)))

(defn relay-explosion [m]
  (explode (assoc m :via :relay)))

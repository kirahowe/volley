(ns volley.gen.order
  "Generators for `volley.order`: run seeds, namespace names and the var
  names of one namespace."
  (:require
   [clojure.test.check.generators :as gen]))

(def seed
  "A run seed in the range `--seed` accepts and Kaocha draws from."
  (gen/choose 0 Integer/MAX_VALUE))

(def namespace-name
  "A namespace name."
  (gen/fmap #(symbol (str "p." (name %) "-test")) gen/symbol))

(def var-names
  "Distinct var names, as one namespace has."
  (gen/vector-distinct gen/symbol {:max-elements 30}))

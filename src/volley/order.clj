(ns volley.order
  "Seeded random order of the vars in a namespace, for Kaocha's randomize
  plugin. Kaocha shuffles the whole test plan from one seed in one process
  (`kaocha.plugin.randomize/rng-sort`). volley's workers each run their own
  namespaces, so each namespace's order comes from the run seed and the
  namespace name alone, and one seed reproduces every namespace's order
  whichever worker runs it.

  Portable: the workers load it on babashka and on the JVM, so the mixing
  uses only `hash` of strings and unchecked long arithmetic, which both
  compute the same way.")

(defn- mix
  "SplitMix64's finalizer: a long that changes in about half its bits when
  any bit of `z` changes."
  [z]
  (let [z (unchecked-multiply (bit-xor z (unsigned-bit-shift-right z 30)) -4658895280553007687)
        z (unchecked-multiply (bit-xor z (unsigned-bit-shift-right z 27)) -7723592293110705685)]
    (bit-xor z (unsigned-bit-shift-right z 31))))

(defn sort-key
  "The position key of var `var-sym` of namespace `ns-sym` under run seed
  `seed`. It depends on nothing else, so adding or filtering out a var
  leaves the order of the others alone."
  [seed ns-sym var-sym]
  (-> (long seed)
      (unchecked-add (long (hash (str ns-sym))))
      mix
      (unchecked-add (long (hash (str var-sym))))
      mix))

(defn shuffle-names
  "`var-syms` of namespace `ns-sym` in the random order run seed `seed`
  picks. The same seed, namespace and names give the same order whatever
  order the names come in."
  [seed ns-sym var-syms]
  (sort-by (juxt #(sort-key seed ns-sym %) str) var-syms))

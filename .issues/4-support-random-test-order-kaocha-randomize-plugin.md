# Support random test order (Kaocha randomize plugin)

## Context

Kaocha's randomize plugin shuffles test order and prints a seed. volley accepts `--randomize`, `--seed` and the plugin, warns once that ordering is not randomized, and runs namespaces in scheduler order and vars in name order. Random order with parallel workers can only be per worker: each worker could shuffle the vars of a namespace from a seed derived from the run seed and the namespace name, so a run is reproducible from one seed.

## Acceptance criteria

- [ ] With the randomize plugin (or `--randomize`), vars inside each namespace run in a seeded random order, and the seed is printed like Kaocha's.
- [ ] `--seed N` reproduces the same per-namespace order.
- [ ] The once-per-run warning goes away; doc/design.md divergences updated.
- [ ] Property test: the order is a permutation and is stable for a seed.

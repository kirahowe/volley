# Recompute the randomize seed when tests.edn changes in watch mode

## Context

Found by QA on volley#4 (not verified). The seed lives in engine-opts, computed once at watch startup, so editing randomize settings in tests.edn mid-watch may not update the seed or turn shuffling off.

## Acceptance criteria

- [ ] Confirm the behaviour; if real, a config reload recomputes randomize/seed.
- [ ] Watch test covering it.

## Log

- 2026-10-18: Landed on main (0c131c9..e5684e9). The bug was real: watch runs kept the startup seed after tests.edn changed. The new pure `watch/run-opts` takes `:seed` and the hook options from the reloaded config, so turning randomize off or changing the seed applies on the next run. A config without a seed falls back to the session seed (Kaocha draws a new one per run; noted in design.md). Added a property, an example test and an e2e watch test. Gate: bb lint clean, bb test 403/403. Still open: other startup options (engine-opts `:config`, `:bindings`) stay frozen across a reload.

# Apply wrap-run hooks to suites and vars, not only namespaces

## Context

Kaocha's `wrap-run` hook wraps the run of every testable: suites, namespaces and vars. volley applies it around each namespace's run only (doc/design.md, Hooks). Hooks that rely on being called per var (timing, bindings) behave differently.

## Acceptance criteria

- [ ] `wrap-run` also wraps each var's run in the worker, matching Kaocha's arity and order.
- [ ] Decide and document suite-level wrapping in a multi-process run.
- [ ] hooks e2e fixture extended to log var-level wrap calls.

## Log

- 2026-10-18: Landed on main (6048b4c..cacfa71). `wrap-run` now wraps each var (pre-test, wrapped run, post-test, as in `kaocha.testable/run-testable`) and each suite once per worker, inside `:kaocha/bindings`; documented in doc/design.md and `volley.hooks/semantics`. Rebased over volley#8: `serve` now reads from volley#8's command-reader queue. Gate green (`bb lint` clean, `bb test` 422/422). Follow-up idea from QA: name a throwing `wrap-run` hook in the error, as pre-test errors are.

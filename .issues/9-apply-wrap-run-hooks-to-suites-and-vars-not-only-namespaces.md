# Apply wrap-run hooks to suites and vars, not only namespaces

## Context

Kaocha's `wrap-run` hook wraps the run of every testable: suites, namespaces and vars. volley applies it around each namespace's run only (doc/design.md, Hooks). Hooks that rely on being called per var (timing, bindings) behave differently.

## Acceptance criteria

- [ ] `wrap-run` also wraps each var's run in the worker, matching Kaocha's arity and order.
- [ ] Decide and document suite-level wrapping in a multi-process run.
- [ ] hooks e2e fixture extended to log var-level wrap calls.

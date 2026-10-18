# Make small JVM suites at least as fast as Kaocha

## Context

On the JVM, the parent and each worker start their own JVM and load the
project's code again. Large suites gain a lot (reader: about 117s with
Kaocha, 57s with volley), but small ones lose:

- hosted-clay (70 tests): 10.3s with Kaocha, 10 to 27s with volley over
  four runs during dogfooding on 2026-10-18 (machine loaded).
- A 23-test app: 2.2 to 2.8s with Kaocha, 2.7s with volley.

`doc/benchmarks.md` records these. Ideas raised during QA:

- Run in the parent's own process when the plan is small (few
  namespaces, or a short total in `.volley/timings.edn`).
- Start fewer workers than the default when there are few namespaces or
  most of the time sits in one namespace.
- Reduce worker startup further: AppCDS or AOT for volley's own
  namespaces, lazy loading in the worker.

## Acceptance criteria

- [ ] On hosted-clay (a copy) volley's median wall time over 5 runs is no
      worse than Kaocha's, measured back to back on a quiet machine.
- [ ] Large suites (reader, secrets) keep their current gains.
- [ ] Whatever heuristic picks in-process or fewer workers is a pure
      function with property tests, and the user can override it
      (`--workers`).
- [ ] `doc/benchmarks.md` updated with the new numbers.

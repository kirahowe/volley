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

## Log

- 2026-10-19: Landed on main (fb6c436..96a9517), together with volley#12. A JVM parent now runs the tests in its own process when recent runs took 10s or less (pure `volley.runtime/worker-plan`, with properties; `--workers` overrides it) and scales out to worker JVMs if a run goes long. It also caches parsed ns forms in `.volley/ns-infos.edn`. hosted-clay: about 6.2s, level with Kaocha within noise (the machine was loaded). Reader and secrets keep their gains. Numbers are in doc/benchmarks.md. The first run with no history still starts worker JVMs. Gate: bb lint clean, bb test 465/465.
- 2026-10-19: Review follow-ups landed on main (055d753..6058968). The worker's stop flag is now per `serve!` call, so a shutdown no longer stops later namespaces; stopped vars count as `:stopped` and fail the run. `serial-ms` decides whether a second in-process try follows a slow one. `end-var!` restores the parent's streams only from its own capture. worker-plan properties restated as invariants. hosted-clay after the merge: volley 6.14s, Kaocha 6.16s (medians of 5, machine loaded). Gate: bb lint clean, bb test 478/478.

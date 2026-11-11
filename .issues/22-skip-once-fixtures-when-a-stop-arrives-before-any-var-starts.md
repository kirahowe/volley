# Skip :once fixtures when a stop arrives before any var starts

## Context

Found in the 0.1.0-alpha.2 review. `test-vars!` in `volley.shell.worker`
checks `stop-requested?` per var, inside the `:once` fixture. When a
`:shutdown` (fail-fast, watch cancel) arrives while a namespace loads, its
`:once` setup and teardown still run for zero vars. A slow fixture (a
database, a container) uses up the 1s grace, the worker is killed, and its
`post-run` hooks are lost, which graceful stop exists to prevent.

## Acceptance criteria

- [x] A stop requested before a namespace's first var runs none of its
      `:once` fixtures, and its vars are reported as stopped.
  - Receipt: `a-stop-before-the-first-var-runs-no-once-fixture` asserts
    zero fixture runs and both vars skipped as `:stopped`; it passes under
    `bb test --focus volley.shell.worker-test` (44 tests, 0 failed).
- [x] Worker test with a fixture that records whether it ran.
  - Receipt: `test/volley/shell/worker_test.clj`, against
    `dev-resources/exec/once_runs_test.clj`, whose `:once` fixture counts
    its runs in `once-runs`.

## Log

- 2026-11-11: `test-vars!` checks `stop-requested?` before the `:once`
  fixtures as well as before each var, so a stop that came first runs
  neither and the vars are sent as `:stopped`. Covered by
  `a-stop-before-the-first-var-runs-no-once-fixture` in `worker_test`,
  with the fixture namespace `exec.once-runs-test`. The namespace still
  loads, and its namespace-level hooks still run around the empty run.

# Skip :once fixtures when a stop arrives before any var starts

## Context

Found in the 0.1.0-alpha.2 review. `test-vars!` in `volley.shell.worker`
checks `stop-requested?` per var, inside the `:once` fixture. When a
`:shutdown` (fail-fast, watch cancel) arrives while a namespace loads, its
`:once` setup and teardown still run for zero vars. A slow fixture (a
database, a container) uses up the 1s grace, the worker is killed, and its
`post-run` hooks are lost, which graceful stop exists to prevent.

## Acceptance criteria

- [ ] A stop requested before a namespace's first var runs none of its
      `:once` fixtures, and its vars are reported as stopped.
- [ ] Worker test with a fixture that records whether it ran.

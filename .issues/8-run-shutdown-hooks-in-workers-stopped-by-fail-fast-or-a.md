# Run shutdown hooks in workers stopped by fail-fast or a watch cancel

## Context

Workers killed by fail-fast, or cancelled by a watch-mode rerun, are killed by handle and run no `post-run` hooks or other shutdown work. Hooks that clean up resources (stop a system, delete temp dirs) therefore don't run in those workers. A graceful stop (send `:shutdown`, wait briefly, then kill) would let them run.

## Acceptance criteria

- [ ] Fail-fast and watch cancel ask workers to stop and wait a bounded time (e.g. 1s) before killing.
- [ ] `post-run` hooks run in those workers when they stop in time.
- [ ] Fail-fast still stops promptly: a test that never returns cannot delay the exit beyond the bound.
- [ ] e2e test with a hook that writes a marker file.

## Log

- 2026-10-18: Fail-fast and watch cancels now send `:shutdown` and give
  workers 1s (`:stop-grace-ms`, `volley.watch/cancel-action`) before
  killing them. The worker reads stdin on its own thread and starts no
  var after a stop, so post-run hooks run. Covered by
  engine_stop_test, watch_cancel_test, a pool test and the
  graceful-stop e2e tests.

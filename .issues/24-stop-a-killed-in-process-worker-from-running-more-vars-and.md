# Stop a killed in-process worker from running more vars and hooks

## Context

Found in the 0.1.0-alpha.2 review. `kill!` in `volley.shell.in-process`
restores the parent's streams, interrupts the thread and reports the exit,
but sets no stop flag: only a `:shutdown` line does
(`volley.shell.worker`). After a watchdog or drain kill, a var that
swallows the interrupt (`Thread/sleep` in a catch-all) lets `test-vars!`
start the namespace's remaining vars, each pointing the parent's
`System/out` at its capture, and `serve!` then runs `post-run` hooks in
the parent after `:worker/exit`, possibly alongside `post-summary`.

## Acceptance criteria

- [x] After `kill!`, the in-process thread starts no further var and runs
      no further hook.
  - Receipt: `a-killed-worker-starts-no-further-var-and-runs-no-shutdown-hook`
    in `in_process_test` kills a worker whose first var swallows the
    interrupt and sees `:var/end`, two vars skipped as `:stopped`,
    `:ns/end`, and no `post-run` call. With the `killed?` argument
    removed it fails: both later vars start and the hook runs once.
- [x] `begin-var!` does not take over `System/out` once the worker was
      killed.
  - Receipt: `a-var-that-starts-after-a-stop-leaves-the-system-streams-alone`
    in `worker_test` (the function is `start-var!`); both pass under
    `bb test --focus volley.shell.worker-test --focus volley.shell.in-process-test`
    (50 tests, 0 failed).

## Log

- 2026-11-11: `serve!` takes a `killed?` predicate, which the in-process
  worker answers from the flag its `kill!` sets. Once it holds, the loop
  counts as stopped, so no further var starts, and it skips the shutdown
  hooks. `start-var!` takes the system streams under
  `system-streams-lock` only while no stop is requested; the kill sets
  its flag before it takes that lock. Hooks around the var that was
  running (`post-test`, the rest of a `wrap-run`) still finish as it
  unwinds; only hooks that had not started are skipped. The handle
  carries `:thread` so a test can wait for the thread to end.

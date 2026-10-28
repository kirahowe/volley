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

- [ ] After `kill!`, the in-process thread starts no further var and runs
      no further hook.
- [ ] `begin-var!` does not take over `System/out` once the worker was
      killed.

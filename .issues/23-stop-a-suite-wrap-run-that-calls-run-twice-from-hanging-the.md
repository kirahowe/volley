# Stop a suite wrap-run that calls run twice from hanging the worker

## Context

Found in the 0.1.0-alpha.2 review. A suite-level `wrap-run` hook that calls
`run` twice (a retry hook, say) makes the worker re-run its first command,
after which `more` calls `.take` on the command queue after the eof or
`:shutdown` was already consumed and blocks for good
(`volley.shell.hooks` around `wrapped-run`, `volley.shell.worker`
`serve!`). The worker is killed at the drain timeout and its shutdown
hooks don't run. Kaocha would run the suite twice. Rare in practice.

## Acceptance criteria

- [ ] A suite `wrap-run` calling `run` twice ends the worker normally,
      either running the suite again as Kaocha does or reporting the
      hook as an error.

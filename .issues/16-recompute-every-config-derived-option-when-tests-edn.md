# Recompute every config-derived option when tests.edn reloads in watch mode

## Context

Found while landing volley#14. After a tests.edn reload, watch mode still uses the startup values of :bindings, :fail-fast?, :capture-output?, :isolate?, :preload?, :junit-xml and :config (which --print-result reads). Only randomize and the seed are recomputed.

## Acceptance criteria

- [ ] run-opts derives all of these from the reloaded config.
- [ ] Property or watch test covering a reload that changes them.

## Log

- 2026-10-18: Landed on main (01ad5cc, e9c9983). New pure `volley.config/engine-opts` returns every config-decided option; watch's `run-opts` merges it over the session options and volley.main uses it for one-shot runs. Each watch run gets a fresh JUnit timestamp via `volley.shell.fs/run-info`. Reporter and colour still keep their startup values. Follow-up worth filing: test paths added by a reloaded tests.edn never reach the parent classpath. Gate: lint clean, 425/425 tests.

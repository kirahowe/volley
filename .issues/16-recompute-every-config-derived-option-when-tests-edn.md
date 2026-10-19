# Recompute every config-derived option when tests.edn reloads in watch mode

## Context

Found while landing volley#14. After a tests.edn reload, watch mode still uses the startup values of :bindings, :fail-fast?, :capture-output?, :isolate?, :preload?, :junit-xml and :config (which --print-result reads). Only randomize and the seed are recomputed.

## Acceptance criteria

- [ ] run-opts derives all of these from the reloaded config.
- [ ] Property or watch test covering a reload that changes them.

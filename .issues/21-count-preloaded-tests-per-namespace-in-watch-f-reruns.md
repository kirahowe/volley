# Count preloaded tests per namespace in watch f reruns

## Context

Found in the 0.1.0-alpha.2 review. `volley.engine/suite-selects` keeps one
select spec per suite (`into {}`, last entry wins) and assumes specs differ
only in `:ns-meta`. Watch mode's `select-plan` breaks that: a namespace
rerun for its failures gets an extra focus pass naming its failed vars.
With `--preload` and two or more such namespaces, each worker's
`count-preloaded!` (`volley.shell.worker`) counts the other preloaded
namespaces with the wrong focus pass, so they count 0 tests. Those counts
are not estimates, so the live footer shows too low a total with no `~`.
Each namespace corrects itself when it starts.

## Acceptance criteria

- [ ] A watch `f` rerun with `--preload` over two namespaces with failures
      shows the right total from the first frame.
- [ ] Covered by a property or example test in `engine_test`.

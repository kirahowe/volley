# Keep failures of vars a watch t filter skips

## Context

Found by QA on volley#5. When a `t` filter skips only some vars of a namespace, watch/outcome puts the namespace in :ran and merge-outcome replaces its whole failure set, so failures of the skipped vars disappear. Repro: q.gen-test has macro tests gen-ok (passes) and gen-bad (fails); first run shows FAIL 1, then `t^gen-ok$` shows PASS, though gen-bad was skipped, not fixed.

## Acceptance criteria

- [ ] Earlier failures of vars whose records are :not-focused skips survive the merge.
- [ ] Watch test for the partial-filter case.

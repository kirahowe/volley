# Keep failures of vars a watch t filter skips

## Context

Found by QA on volley#5. When a `t` filter skips only some vars of a namespace, watch/outcome puts the namespace in :ran and merge-outcome replaces its whole failure set, so failures of the skipped vars disappear. Repro: q.gen-test has macro tests gen-ok (passes) and gen-bad (fails); first run shows FAIL 1, then `t^gen-ok$` shows PASS, though gen-bad was skipped, not fixed.

## Acceptance criteria

- [ ] Earlier failures of vars whose records are :not-focused skips survive the merge.
- [ ] Watch test for the partial-filter case.

## Log

- 2026-10-18: Landed on main (8e25b07..577f32c). `watch/outcome` now reports the vars a filter skipped as `:not-focused`, and `merge-outcome` keeps their earlier failures, so a partial `t` filter no longer turns FAIL into PASS. This sits alongside volley#5's rule that a fully unselected namespace stays out of `:ran`. Added a property test and a watch test for the partial-filter case, and updated design.md. Gate: bb lint clean, bb test 400/400.

# Run a namespace found under two test paths twice, as Kaocha does

## Context

When the same namespace is found under two test paths (for example `test` and `test2` both define `g.dup-test`), Kaocha runs it twice and counts its tests twice; volley runs it once. Found in QA round 2 (probe `gen`: Kaocha 13 tests, volley 12). Whether to match Kaocha or keep this as a documented divergence is a decision.

## Acceptance criteria

- [ ] Decide: match Kaocha, or document the divergence in doc/design.md and the README.
- [ ] If matching, a Kaocha diff case with a duplicated namespace.

## Log

- 2026-10-18: Kept the divergence instead of matching Kaocha. volley runs
  the namespace once, from the file that comes first on the classpath.
  Documented it in doc/design.md and the README, and pinned it with the
  e2e test `a-namespace-under-two-test-paths-runs-once`.

# Recompute the randomize seed when tests.edn changes in watch mode

## Context

Found by QA on volley#4 (not verified). The seed lives in engine-opts, computed once at watch startup, so editing randomize settings in tests.edn mid-watch may not update the seed or turn shuffling off.

## Acceptance criteria

- [ ] Confirm the behaviour; if real, a config reload recomputes randomize/seed.
- [ ] Watch test covering it.

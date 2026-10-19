# Stabilise the documentation-output linearity test under load

## Context

engine-perf-test/documentation-output-is-linear-in-namespaces failed intermittently while several agents ran bb test at once during the wave-2 run (reported by the volley#15 agent). 6989bb6 already loosened a similar test.

## Acceptance criteria

- [ ] The test passes reliably with the machine under parallel load, while still catching a quadratic regression.

## Log

- 2026-10-19: The linearity tests in engine_perf_test.clj no longer time
  anything. They count the namespace records that render/candidates returns
  over a synthetic run and compare 4n namespaces with n. A linear engine
  gives exactly 4.0, and a run that re-renders every namespace on each
  ns/end gives about 14, against a bound of 8. The count is the same on
  every run, so load can't change it. It only covers the renderer's
  per-namespace walk.

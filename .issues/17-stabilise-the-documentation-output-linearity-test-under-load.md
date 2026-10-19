# Stabilise the documentation-output linearity test under load

## Context

engine-perf-test/documentation-output-is-linear-in-namespaces failed intermittently while several agents ran bb test at once during the wave-2 run (reported by the volley#15 agent). 6989bb6 already loosened a similar test.

## Acceptance criteria

- [ ] The test passes reliably with the machine under parallel load, while still catching a quadratic regression.

# Find the 1-in-40 bb test failure seen after the volley#2 fixes

## Context

On main at c05d4d8, the first bb test run after landing the volley#2 fix pass reported 478 tests with 1 failed. The output was not captured. 38 further runs (including one with the pre-fix .volley/ cache restored) all passed. Count is now stable at 478, so this is a separate flake, likely timing-sensitive.

## Acceptance criteria

- [ ] Identify the failing test (e.g. loop bb test with output saved, under parallel CPU load).
- [ ] Make it deterministic or loosen it without losing what it checks.

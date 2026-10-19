# Name the wrap-run hook when it throws

## Context

Found by QA on volley#9. A wrap-run hook that throws is reported as "<ns> failed in a fixture" with the raw exception, while a throwing pre-test hook is reported as "the pre-test hook X failed". The var that threw, and those after it, drop out of the counts with no "not run:" line. Namespace-level wrap-run already behaved this way before volley#9.

## Acceptance criteria

- [ ] A throwing wrap-run hook is reported by name, like pre-test.
- [ ] Vars that did not run are listed as not run.

## Log

- 2026-10-18: A wrap-run hook that throws is now named in the error, the
  same way as pre-test, and an error from an inner hook or a fixture passes
  through unchanged. Vars that never started after an escaped error are
  listed as "not run:". Kaocha doesn't catch these errors at all, so
  doc/design.md notes that the wording follows pre-test. Covered by new
  tests in worker_test.clj.

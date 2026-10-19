# Name the wrap-run hook when it throws

## Context

Found by QA on volley#9. A wrap-run hook that throws is reported as "<ns> failed in a fixture" with the raw exception, while a throwing pre-test hook is reported as "the pre-test hook X failed". The var that threw, and those after it, drop out of the counts with no "not run:" line. Namespace-level wrap-run already behaved this way before volley#9.

## Acceptance criteria

- [ ] A throwing wrap-run hook is reported by name, like pre-test.
- [ ] Vars that did not run are listed as not run.

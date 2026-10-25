# Add test paths from a reloaded tests.edn to the watch parent classpath

## Context

Raised in review of volley#16. add-test-paths! runs only at watch startup, so a post-summary hook or a new suite test path added in a reloaded tests.edn never reaches the parent classpath. Kaocha also reloads the reporter and :color? on each watch run (kaocha.watch/reload-config); volley keeps their startup values, which is documented.

## Acceptance criteria

- [x] A reload that adds test paths makes them loadable in the parent.
  - Receipt: `watch-puts-a-reloaded-configs-test-paths-on-the-parent-classpath` in `test/volley/e2e/watch_reruns_test.clj` fails without the fix and passes with it; `bb test` and `bb test:jvm` pass (489 tests).
- [x] Decide whether to reload the reporter and colour too, and document it.
  - Receipt: kept at their startup values; the reason is in `doc/design.md` (watch step 4).

## Log

- 2026-10-24: Watch reload calls `add-hook-paths!` (in `volley.main`), the
  same function startup uses; `add-test-paths!` now skips paths already on
  the classpath. Covered by
  `watch-puts-a-reloaded-configs-test-paths-on-the-parent-classpath`, which
  fails without the fix.
- Reporter and colour keep their startup values: the reporter sets up the
  terminal once per session. The project's own reporters already follow a
  reload through the configure message. Documented in `doc/design.md`.

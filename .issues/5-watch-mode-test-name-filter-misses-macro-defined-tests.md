# Watch mode test-name filter misses macro-defined tests

## Context

Watch mode's `t` key filters by test-name regex. The planning step drops namespaces judged only by their statically visible `deftest`s, so a test defined by a macro whose name matches the pattern is missed. The rest of selection already handles tests the parser cannot see through a wildcard child (see doc/design.md, invisible tests). Found by the QA fix pass on 2026-10-18.

## Acceptance criteria

- [ ] A macro-defined test whose name matches the `t` pattern runs.
- [ ] Namespaces with no visible match are only skipped when they cannot contain hidden tests, or the filter is decided in the worker after loading.
- [ ] Regression test in the watch e2e or select tests using the `generated` fixture.

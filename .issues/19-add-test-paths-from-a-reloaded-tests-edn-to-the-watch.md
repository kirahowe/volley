# Add test paths from a reloaded tests.edn to the watch parent classpath

## Context

Raised in review of volley#16. add-test-paths! runs only at watch startup, so a post-summary hook or a new suite test path added in a reloaded tests.edn never reaches the parent classpath. Kaocha also reloads the reporter and :color? on each watch run (kaocha.watch/reload-config); volley keeps their startup values, which is documented.

## Acceptance criteria

- [ ] A reload that adds test paths makes them loadable in the parent.
- [ ] Decide whether to reload the reporter and colour too, and document it.

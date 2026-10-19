# Notice deps.edn changes in watch mode and the JVM classpath cache

## Context

Two cache gaps: watch mode does not watch deps.edn (a dependency change needs a restart), and the `--runtime jvm` classpath cache key covers only the project's deps.edn and the aliases, so changes to a `:local/root` dependency's deps.edn or to `~/.clojure/deps.edn` go unnoticed until `.volley/classpath-*.txt` is deleted. Both are documented in the README.

## Acceptance criteria

- [ ] Watch mode notices deps.edn (and bb.edn) changes and restarts its workers with a recomputed classpath.
- [ ] The classpath cache key includes the deps.edn files of `:local/root` dependencies and the user-level deps.edn.
- [ ] README notes about deleting the cache removed or updated.

## Log

- 2026-10-18: Landed on main (23a637a..69a0f29). The `--runtime jvm` classpath cache key now covers transitive `:local/root` deps.edn files (including `:default-deps`) and the user-level deps.edn. Watch mode reloads on deps.edn or bb.edn changes and recomputes the worker classpath. README and design.md updated. Removed entries stay on the classpath until restart, and that is documented. Gate: bb lint clean, bb test 384/384.

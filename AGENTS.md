# volley

A parallel, live-output `clojure.test` runner for babashka that can stand in
for Kaocha. Read `doc/design.md` before changing anything; it defines the
architecture and the data contracts between namespaces.

## Commands

- `bb test` runs volley's own test suite with volley itself (`tests.edn`).
- `bb test:bootstrap` runs the same suite with plain clojure.test, for when
  volley is too broken to run itself.
- `bb test:jvm` runs the same suite with the parent on the JVM and fails
  on reflection warnings in `src` (every shell namespace sets
  `*warn-on-reflection*`).
- `bb test:diff` compares volley with Kaocha (downloads Kaocha).
- `bb lint` runs clj-kondo over `src` and `test`. It must report no errors
  or warnings.

## Rules

- **Functional core, imperative shell.** Everything under `volley.*` except
  `volley.shell.*` and `volley.main` is pure: no I/O, atoms, dynamic
  rebinding, clocks, randomness or `System/` calls. Pass those things in as
  arguments. Shell namespaces stay thin and delegate decisions to the core.
- **Minimize state.** Prefer an immutable value threaded through a loop or
  `reduce` over an atom. The parent has one event queue; don't add more
  shared mutable objects without updating `doc/design.md`.
- **Property tests first.** Every pure namespace gets test.check properties
  (`clojure.test.check.clojure-test/defspec`) for its invariants, with
  generators in `test/volley/gen/<area>.clj` so tests can share them. Use example-based
  `deftest`s for specific Kaocha-compatibility cases and for shell code.
- **Style.** Follow the Clojure Style Guide (https://guide.clojure.style).
  Idiomatic Clojure: small functions over data, threading macros where they
  read better, destructuring, no `def` inside functions, no deeply nested
  `let`/`if` ladders, no reflection-heavy interop, no `:refer :all`, no
  unused requires. Docstrings on public vars; private helpers with `defn-`.
- **Portable worker.** `volley.shell.worker` and every pure namespace must run
  on both babashka and the JVM. Don't use babashka-only libraries there.
- **Kaocha is the reference.** When matching Kaocha behaviour, read its
  source in `~/.m2/repository/lambdaisland/kaocha/1.91.1392/` rather than
  guessing, and cite the Kaocha function in a test name or comment.
- **Other projects are read-only.** To test against a project under
  `~/code/projects`, copy it into a scratch directory first (without `.git`
  or `.jj`) and run there. Never modify, commit to, or run tests inside the
  original.
- **Commits.** Commit at each passing checkpoint with `commitp -m "..."`
  (one `-m`, subject line, blank line, body explaining why). Load the
  `humanizer` skill before writing commit messages or docs.

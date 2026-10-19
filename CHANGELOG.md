# Changelog

All notable changes to volley are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and volley uses
[semantic versioning](https://semver.org/) once it leaves alpha.

## [Unreleased]

### Added

- Random test order. With Kaocha's randomize plugin (on by default) or
  `--randomize`, the vars inside each namespace run in an order shuffled
  from the run seed and the namespace's name, so babashka and JVM workers
  agree on it. `--seed N` reproduces that order, a failed run prints
  `Randomized with --seed N` as Kaocha does, and load hooks see the vars
  in run order. Namespaces still start slowest first. The warning that
  volley does not randomize is gone. (volley#4)
- When volley creates `.volley/`, it writes a `.gitignore` containing `*`
  inside it, so git and jj ignore the cache without a project entry.
  (volley#1)
- Watch mode reloads when the project's `deps.edn` or `bb.edn` changes
  and computes the workers' classpath again. Removed dependencies and
  paths stay on the classpath until a restart. (volley#10)

### Changed

- The `--runtime jvm` classpath cache key also covers the `deps.edn` of
  every `:local/root` dependency, including those in `:default-deps`, and
  the user-level `deps.edn`. (volley#10)
- A plugin whose namespace loads but defines no plugin stops the run with
  exit 249, as in Kaocha, instead of a warning. volley looks for the
  definition through the namespace's requires and wrapper macros, and
  ignores commented-out ones. (volley#7)
- The docs now record a deliberate divergence from Kaocha: a namespace
  that two test paths define runs once, from the file that comes first on
  the classpath, where Kaocha runs it twice. (volley#11)
- Fail-fast and a watch-mode cancel ask busy workers to stop and give
  them 1s before killing them. A worker that stops in time finishes its
  current var and runs its `post-run` hooks. (volley#8)
- `wrap-run` hooks wrap each var's run as well as each namespace's, as in
  Kaocha. A suite-level `wrap-run` is called once per worker process
  rather than once per run. (volley#9)
- A `wrap-run` hook that throws is reported by name, and the vars it kept
  from running are listed as not run. (volley#18)

### Fixed

- Watch mode's `t` filter now runs matching tests that a macro defines;
  the worker applies the pattern after loading the namespace. Failures of
  a namespace the filter skips stay counted. (volley#5)
- A namespace whose vars are all pending runs its `:once` fixtures, as in
  Kaocha. A `^:kaocha/pending` namespace still runs none. (volley#6)
- When a watch `t` filter skips some vars of a namespace, their earlier
  failures stay counted instead of disappearing. (volley#13)
- In watch mode, a reloaded `tests.edn` updates the randomize setting and
  the seed for later runs. (volley#14)
- In watch mode, every option `tests.edn` decides (workers, fail-fast,
  output capture, isolation, bindings, the JUnit report and hooks) comes
  from the reloaded config. The reporter and colour keep their startup
  values. (volley#16)

## [0.1.0-alpha.1] - 2026-10-18

First alpha. volley is a `clojure.test` runner that reads Kaocha's
`tests.edn` and command-line flags.

### Added

- Parallel test runs. The parent process plans the run and hands test
  namespaces to worker processes; workers stay alive across namespaces.
  The default worker count is the CPU count, capped at 8 on babashka and 4
  on the JVM. `--workers N` overrides it, and `--isolate` starts a fresh
  worker for each namespace.
- Workers on babashka or on the JVM. The parent's runtime is the default;
  `--runtime bb|jvm` chooses the other one. From a babashka parent,
  `--aliases` picks the deps.edn aliases for the JVM classpath, which is
  cached in `.volley/`. A JVM parent forwards its own JVM options to its
  workers. A worker loads no source-parsing code, so a JVM worker is
  ready in about 0.4s. Workers frame each protocol event with newlines and
  a per-worker nonce, so a child process a test starts can write to the
  inherited stdout without hiding an event or passing for one.
- Live output: a redrawn view on a terminal with one line per running
  namespace, and append-only lines when stdout is not a terminal. Failures
  show expected and actual values, a structural diff for equality
  assertions, captured output, and the user's call stack, without the
  babashka interpreter's wrappers. Failed `defspec` properties show the
  seed and the shrunk input. The run ends with a summary, the slowest
  tests, and a command that reruns the failures (keeping
  `--config-file`). Output printed outside capture is shown in full, and
  load-time output that several workers print appears once. Output costs
  time linear in the number of namespaces.
- Watch mode (`--watch`). volley polls the test and source paths, works out
  which test namespaces depend on a changed file from their `ns` forms,
  and reruns only those in fresh workers. Keys rerun everything, rerun
  failures, or filter by namespace or test name. Each run ends with the
  counts line of the summary.
- Kaocha compatibility: `tests.edn` with `#kaocha/v1` and the reader tags
  `#profile`, `#env`, `#envf`, `#hostname` and `#user`; suite ids;
  `--focus`, `--skip`, `--focus-meta`, `--skip-meta`, `--fail-fast`,
  `--config-file`, `--[no-]color`, `--[no-]capture-output`, `--reporter`,
  `--print-config`, `--print-test-plan`, `--print-result`; Kaocha's exit
  codes, including 248 for a plugin that cannot be loaded and 252 for a
  config that fails Kaocha's spec; `:kaocha/bindings`; `^:kaocha/pending`
  and `:kaocha.testable/skip`. Values volley cannot use, such as
  `:preload? "yes"` or a `:kaocha/bindings` vector, stop the run with
  exit 250.
- Kaocha's counting: a test var that makes no assertion fails with
  Kaocha's "Test ran without assertions" message; only pending tests
  count as skipped, and tests the filters leave out are not counted; a
  focus that matches nothing prints Kaocha's "All N tests were skipped"
  warning.
- `--print-config`, `--print-test-plan` and `--print-result` print
  Kaocha's shapes, with volley's own settings under `:volley/*` keys.
- Kaocha's hooks plugin, run in each worker (and `post-summary` in the
  parent), and `kaocha.plugin/junit-xml` built in. A hook that fails is
  named in the report, and a `post-run` hook that fails in several
  workers counts once.
- Reporters: Kaocha's own map to volley's output, and a project's reporter
  (`--reporter my.app/report`) runs in the workers and gets every
  `clojure.test` event. A reporter that does not resolve exits 254.
- `:volley/worker-setup` in `tests.edn`: functions each worker calls before
  it loads its first test namespace, for per-process isolation.
- `--preload`, which makes each worker load every test namespace first, as
  Kaocha does, setting up each suite before loading its namespaces.
- `^:synchronized` and `^:volley/serial` namespaces, which run alone after
  the parallel batch.
- Timings kept in `.volley/timings.edn` so the next run starts with the
  slowest namespaces.
- `bin/volley`, a binstub for babashka projects.
- A differential test suite (`bb test:diff`) that runs Kaocha 1.91.1392 and
  volley on the fixture projects in `dev-resources/projects` and compares
  executed vars, outcomes, assertion counts, test and pending totals, and
  exit codes.

### Known limitations

- Only `clojure.test` suites. No ClojureScript, no coverage.
- Kaocha's notifier, gc-profiling, cloverage and similar plugins are
  accepted with their options and do nothing; one warning names them.
- `--randomize` and `--seed` are accepted with a warning; volley does not
  randomize test order.
- On the JVM, a suite that takes Kaocha under about 10 seconds may be no
  faster under volley, or a little slower.
- volley differs from Kaocha in a few deliberate ways (load errors,
  `System/exit` in a test, fail-fast inside a test, load order, the
  `--print-*` maps). See "Deliberate divergences from Kaocha" in
  [doc/design.md](doc/design.md).

[Unreleased]: https://github.com/kirahowe/volley/compare/v0.1.0-alpha.1...HEAD
[0.1.0-alpha.1]: https://github.com/kirahowe/volley/releases/tag/v0.1.0-alpha.1

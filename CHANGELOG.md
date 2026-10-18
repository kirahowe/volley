# Changelog

All notable changes to brisk are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and brisk uses
[semantic versioning](https://semver.org/) once it leaves alpha.

## [0.1.0-alpha.1] - unreleased

First alpha. brisk is a `clojure.test` runner that reads Kaocha's
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
  cached in `.brisk/`. A JVM parent forwards its own JVM options to its
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
- Watch mode (`--watch`). brisk polls the test and source paths, works out
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
  and `:kaocha.testable/skip`. Values brisk cannot use, such as
  `:preload? "yes"` or a `:kaocha/bindings` vector, stop the run with
  exit 250.
- Kaocha's counting: a test var that makes no assertion fails with
  Kaocha's "Test ran without assertions" message; only pending tests
  count as skipped, and tests the filters leave out are not counted; a
  focus that matches nothing prints Kaocha's "All N tests were skipped"
  warning.
- `--print-config`, `--print-test-plan` and `--print-result` print
  Kaocha's shapes, with brisk's own settings under `:brisk/*` keys.
- Kaocha's hooks plugin, run in each worker (and `post-summary` in the
  parent), and `kaocha.plugin/junit-xml` built in. A hook that fails is
  named in the report, and a `post-run` hook that fails in several
  workers counts once.
- Reporters: Kaocha's own map to brisk's output, and a project's reporter
  (`--reporter my.app/report`) runs in the workers and gets every
  `clojure.test` event. A reporter that does not resolve exits 254.
- `:brisk/worker-setup` in `tests.edn`: functions each worker calls before
  it loads its first test namespace, for per-process isolation.
- `--preload`, which makes each worker load every test namespace first, as
  Kaocha does, setting up each suite before loading its namespaces.
- `^:synchronized` and `^:brisk/serial` namespaces, which run alone after
  the parallel batch.
- Timings kept in `.brisk/timings.edn` so the next run starts with the
  slowest namespaces.
- `bin/brisk`, a binstub for babashka projects.
- A differential test suite (`bb test:diff`) that runs Kaocha 1.91.1392 and
  brisk on the fixture projects in `dev-resources/projects` and compares
  executed vars, outcomes, assertion counts, test and pending totals, and
  exit codes.

### Known limitations

- Only `clojure.test` suites. No ClojureScript, no coverage.
- Kaocha's notifier, gc-profiling, cloverage and similar plugins are
  accepted with their options and do nothing; one warning names them.
- `--randomize` and `--seed` are accepted with a warning; brisk does not
  randomize test order.
- On the JVM, a suite that takes Kaocha under about 10 seconds may be no
  faster under brisk, or a little slower.
- brisk differs from Kaocha in a few deliberate ways (load errors,
  `System/exit` in a test, fail-fast inside a test, load order, the
  `--print-*` maps). See "Deliberate divergences from Kaocha" in
  [doc/design.md](doc/design.md).

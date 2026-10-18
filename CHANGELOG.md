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
  workers.
- Live output: a redrawn view on a terminal with one line per running
  namespace, and append-only lines when stdout is not a terminal. Failures
  show expected and actual values, a structural diff for equality
  assertions, captured output, and the user's call stack. Failed
  `defspec` properties show the seed and the shrunk input. The run ends
  with a summary, the slowest tests, and a command that reruns the failures.
- Watch mode (`--watch`). brisk polls the test and source paths, works out
  which test namespaces depend on a changed file from their `ns` forms,
  and reruns only those in fresh workers. Keys rerun everything, rerun
  failures, or filter by namespace or test name.
- Kaocha compatibility: `tests.edn` with `#kaocha/v1` and the reader tags
  `#profile`, `#env`, `#envf`, `#hostname` and `#user`; suite ids;
  `--focus`, `--skip`, `--focus-meta`, `--skip-meta`, `--fail-fast`,
  `--config-file`, `--[no-]color`, `--[no-]capture-output`, `--reporter`,
  `--print-config`, `--print-test-plan`, `--print-result`; Kaocha's exit
  codes; `:kaocha/bindings`; `^:kaocha/pending` and `:kaocha.testable/skip`.
- Kaocha's hooks plugin, run in each worker (and `post-summary` in the
  parent), and `kaocha.plugin/junit-xml` built in.
- `:brisk/worker-setup` in `tests.edn`: functions each worker calls before
  it loads its first test namespace, for per-process isolation.
- `--preload`, which makes each worker load every test namespace first, as
  Kaocha does.
- `^:synchronized` and `^:brisk/serial` namespaces, which run alone after
  the parallel batch.
- Timings kept in `.brisk/timings.edn` so the next run starts with the
  slowest namespaces.
- `bin/brisk`, a binstub for babashka projects.
- A differential test suite (`bb test:diff`) that runs Kaocha 1.91.1392 and
  brisk on the fixture projects in `dev-resources/projects` and compares
  executed vars, outcomes, assertion counts and exit codes.

### Known limitations

- Only `clojure.test` suites. No ClojureScript, no coverage.
- Kaocha plugins other than filter, capture-output, randomize,
  print-invocations, profiling, hooks and junit-xml are ignored with a
  warning. `--randomize` and `--seed` are accepted and do nothing.
- Reporters other than `kaocha.report/documentation` fall back to brisk's
  own output with a warning.
- brisk differs from Kaocha in a few deliberate ways (load errors,
  `System/exit` in a test, fail-fast inside a test, load order). See "Deliberate divergences
  from Kaocha" in [doc/design.md](doc/design.md).

# brisk

A test runner for `clojure.test` on babashka and the JVM. It runs test
namespaces in parallel worker processes, shows each namespace as it
finishes, and reads Kaocha's `tests.edn` and command-line flags, so a
Kaocha suite runs unchanged.

brisk is alpha software.

## Install

Add brisk to your project's `bb.edn` as a git dependency:

```clojure
{:paths ["src" "test"]
 :deps {io.github.kirahowe/brisk {:git/url "https://github.com/kirahowe/brisk"
                                  :git/sha "<commit sha>"}}
 :tasks {test {:doc "Run the tests"
               :requires ([brisk.main])
               :task (apply brisk.main/-main *command-line-args*)}}}
```

Then run `bb test`, or `bb -m brisk.main` directly. To get a `bin/brisk`
binstub like Kaocha's `bin/kaocha`, copy [`bin/brisk`](bin/brisk) into your
project. It runs `bb -m brisk.main "$@"` with your `bb.edn`.

Workers start with the parent's classpath, so your `bb.edn` `:paths` and
`:deps` apply to the tests. Like Kaocha, brisk also adds each suite's
`:test-paths` to the classpath. It does not add `:source-paths`; those
must be on the classpath through `bb.edn`, as with Kaocha.

## Moving from Kaocha on the JVM

Most Kaocha projects run it with `clojure -M:test`. Change the alias to run
`brisk.main` instead of `kaocha.runner`, and add brisk to the dependencies:

```clojure
{:aliases
 {:test {:extra-paths ["test"]
         :extra-deps {io.github.kirahowe/brisk {:git/url "https://github.com/kirahowe/brisk"
                                                :git/sha "<commit sha>"}}
         :main-opts ["-m" "brisk.main"]}}}
```

`clojure -M:test` then runs brisk with the same `tests.edn` and flags
(`--focus`, `--fail-fast`, `--print-result` and the rest). The parent runs
on the JVM, so the workers are JVM processes too:

- **Classpath.** Each worker gets the parent's classpath (`java.class.path`)
  plus the suite's `:test-paths`, so the alias's `:extra-paths` and
  `:extra-deps` apply.
- **JVM options.** Each worker starts with the options the parent was
  started with, from the alias's `:jvm-opts` or `-J` flags: heap size, `-D`
  properties, `--add-opens`, `--enable-native-access`, GC and `-XX` flags,
  agents. Some are dropped because a second JVM would fight the parent for
  the same port or file: debugger agents (`-agentlib:jdwp`, `-Xdebug`), JMX
  remote ports, socket REPL properties (`-Dclojure.server.*`), JFR and
  `-Xlog`, `-XX:ErrorFile` and `-XX:HeapDumpPath`. Every worker also gets
  `-XX:TieredStopAtLevel=1`, which makes short runs faster; a forwarded
  setting for the same option comes later and wins. With `-Xmx2g` and four
  workers the tests can use up to four times that heap.
- **Worker count.** The default is the CPU count, at most 4 (on babashka,
  at most 8). brisk's JVM mode adds about half a second to a run (the parent
  and a worker both start a JVM), and every worker loads your source again,
  so extra workers pay off only on suites that run for many seconds. `--workers N` overrides it.
- **Pooling.** All workers start together at the beginning, load
  `clojure.test` before they report ready, and then stay alive across
  namespaces. `--isolate` starts a JVM per namespace and prints a warning
  because it is slow.

On the JVM brisk needs `borkdude/edamame`, `babashka/fs` and
`babashka/process`; its `deps.edn` lists them and Clojure resolves them.

### JVM workers from a babashka parent

`bb -m brisk.main --runtime jvm` keeps the parent on babashka and runs the
tests on the JVM. brisk runs `clojure -Spath` for the aliases in
`--aliases` (for example `--aliases :test:dev`; the default is `:test` when
your `deps.edn` defines it, else none) and starts workers with `java -cp`
and the result. The `:jvm-opts` of those aliases go to the workers.

The classpath is cached in `.brisk/classpath-<hash>.txt`, keyed on a hash of
`deps.edn` and the aliases. It is recomputed when either changes or a jar on
it is gone. A change to a `:local/root` dependency's own `deps.edn`, or to
`~/.clojure/deps.edn`, is not noticed; delete `.brisk/classpath-*.txt`
after one.

`--runtime bb` from a JVM parent starts babashka workers.

## Moving from Kaocha on babashka

Replace

```
bb -m kaocha.runner/-main
```

with

```
bb -m brisk.main
```

and remove `lambdaisland/kaocha` from your dependencies if nothing else
uses it. `tests.edn` and the flags stay the same: suite ids, `--focus`,
`--skip`, `--focus-meta`, `--skip-meta`, `--fail-fast`, `--config-file`,
`--[no-]color`, `--[no-]capture-output`, `--reporter`, `--print-config`,
`--print-test-plan` and `--print-result`. Exit codes follow Kaocha's.

Kaocha options brisk does not support print a warning instead of being
ignored silently. That includes plugins other than filter, capture-output,
randomize, print-invocations and profiling, and reporters other than
`kaocha.report/documentation`. The other reporters fall back to brisk's
own output.

`--fail-fast` stops at the first failure: other workers are killed and
their namespaces are listed as interrupted, and no test after the failing
one runs. Where brisk behaves differently from Kaocha on purpose (a
namespace that fails to load, fail-fast inside a failing test, tests that
call `System/exit`), see "Deliberate divergences" in
[doc/design.md](doc/design.md).

## Flags of its own

- `--workers N`: number of worker processes (default: CPU count, at most 8
  on babashka and at most 4 on the JVM).
- `--isolate`: start a fresh worker for every namespace.
- `--runtime bb|jvm`: the worker runtime. Default: the runtime brisk itself
  runs on.
- `--aliases :a:b`: deps.edn aliases for the classpath of JVM workers
  started from babashka.

## Output

On a terminal brisk redraws a live view: one line per running namespace,
a line for each namespace as it finishes, and failures as they happen.
When stdout is not a terminal it prints append-only lines instead, one per
finished namespace followed by its failures. Both end with a summary, the
slowest tests and a `--focus` command to rerun the failures.
`--reporter documentation` prints one line per test var, grouped by
namespace.

Colour is on for terminals, off otherwise, forced with `--color`, and
always off when `NO_COLOR` is set.

Namespace durations are kept in `.brisk/timings.edn`. The next run starts
the slowest namespaces first. Add `.brisk/` to your `.gitignore`.

## Watch mode

`--watch` (or `:watch? true` in `tests.edn`) runs everything once and then
keeps running. brisk polls the modification times of every file under the
suites' `:test-paths` and `:source-paths`, and of `tests.edn`, every 200ms.
When files change it works out which test namespaces depend on them (from
the `ns` forms) and reruns just those in fresh worker processes, so no
state survives between runs. New and deleted test files are picked up. A
change to `tests.edn` reloads the config, rebuilds the workers' classpath
from its test paths, and reruns everything. Watch mode works with JVM
workers too (`--runtime jvm`, or a JVM parent). It does not watch
`deps.edn`; restart it after changing dependencies. If a run is
still going when files change, it is cancelled and replaced.

Each run ends with a status line such as
`PASS  Waiting for file changes...  press h to show help, q to quit`
(`FAIL  2 failures` when something is failing; failures from earlier runs
stay counted until their namespace passes again). On a terminal:

| Key | Action |
|---|---|
| `a` | rerun all |
| `f` | rerun only the failures |
| `p` | filter namespaces by regex (Enter applies, Esc cancels, empty clears) |
| `t` | filter test names by regex |
| `Enter` | rerun the last selection |
| `c` | clear filters |
| `h` | help |
| `q` | quit |

Filters combine with the config's `:focus` and `:skip`; they never widen
it. When stdin is not a terminal, keys are off and watch mode reacts to
file changes only; stop it with Ctrl-C or a signal.

## Serial tests

A namespace marked `^:synchronized` or `^:brisk/serial` (on the ns form or
any of its test vars) runs after the parallel batch, alone.

## Developing brisk

- `bb test`: brisk's own suite, run by brisk (`tests.edn`).
- `bb test:jvm`: the same suite with brisk itself running on the JVM
  (`clojure -M:test -m brisk.main`).
- `bb test:bootstrap`: the same suite with plain `clojure.test`.
- `bb test:diff`: compares brisk with Kaocha 1.91.1392 itself (downloads
  Kaocha).
- `bb lint`: clj-kondo.
- `bb demo`: replays a scripted run through the live view.

Read `AGENTS.md` and `doc/design.md` before changing anything.

# brisk design

brisk is a test runner for `clojure.test` that runs on babashka and on the
JVM. It runs test namespaces in parallel worker processes, shows results
live as they happen, and has a watch mode with vitest-style keys. Moving from
Kaocha takes one binstub change: brisk reads `tests.edn` and accepts
Kaocha's CLI flags.

"brisk" is a working name.

## Goals for the alpha

1. Run an existing Kaocha suite (`tests.edn`, `clojure.test` tests) unchanged,
   picking the same tests and producing the same pass/fail results.
2. Run namespaces in parallel across worker processes. A test that is safe
   in a single-threaded run is safe here, because each worker runs one
   namespace at a time in its own process.
3. Show live output: one line per running namespace, failures as soon as
   they happen, a summary at the end with the slowest tests.
4. Watch mode: rerun only the test namespaces affected by a change, in fresh
   processes, with keys to rerun all, rerun failures, and filter by
   namespace or test name.
5. Work in CI: no live redrawing when stdout isn't a terminal, and correct
   exit codes.

Not in the alpha: Kaocha plugins beyond the ones listed below, test types
other than `clojure.test`, ClojureScript, coverage.

## Architecture

```
            ┌─────────────── parent process (bb or JVM) ──────────┐
 argv ──▶   │ cli ─▶ config ─▶ discover ─▶ select ─▶ schedule     │
 tests.edn  │                                  ▲         │        │
            │                     run-state ◀──┴─ events │        │
            │                         │                  ▼        │
            │                       render ─▶ terminal   pool ────┼──▶ worker 1..N
            └─────────────────────────────────────────────────────┘    (bb or java)
```

The parent does all planning, scheduling, state and printing. Workers only
load namespaces and run tests, reporting what happens as events.

### Functional core, imperative shell

This is structural, not a guideline:

- Every namespace under `brisk.*` **except** `brisk.shell.*` and `brisk.main`
  is pure: no I/O, no atoms, no dynamic rebinding, no `System/` calls, no
  clocks, no randomness. Inputs come in as arguments (file contents as
  strings, timestamps as numbers, terminal width as a number) and results go
  out as data.
- `brisk.shell.*` and `brisk.main` do the I/O and keep the side effects as
  thin as possible. They call into the core for every decision.
- The parent's state is one immutable value threaded through a single event
  loop: `(reduce step state events)` in spirit. The one queue the loop reads
  from is the only shared mutable object, plus the OS process handles.

### Namespaces

Pure core:

| Namespace | Responsibility |
|---|---|
| `brisk.cli` | argv → options map, or a list of errors. Kaocha flags plus brisk's own. |
| `brisk.config` | `tests.edn` text (+ CLI options) → normalized config. Kaocha's reader tags and defaults. |
| `brisk.discover` | File path + file text → namespace info (name, requires). Path filtering by `ns-patterns`. |
| `brisk.select` | Config + namespace infos → the plan's namespaces; var metadata → run/skip decision. |
| `brisk.deps-graph` | Namespace infos → dependency graph; changed files → affected test namespaces. |
| `brisk.schedule` | Pure scheduler state machine: queue, workers, assignments. |
| `brisk.protocol` | Encode and decode messages between parent and worker. |
| `brisk.run-state` | Fold events into the run's state: counts, failures, durations, exit code. |
| `brisk.render` | Run state → text: live frame, CI lines, failure reports, diffs, summary. |
| `brisk.engine` | The parent's event loop: `(step state input) → {:state :effects}`, composing schedule, run-state and render; `preflight` for everything before the loop. |
| `brisk.watch` | Watch mode: mtime snapshot diffing, graph updates, the rerun plan (`select-plan`), and the UI state machine `(step state input) → {:state :effects}` for keys, file changes and finished runs. |
| `brisk.runtime` | Which worker runtime and how many workers by default; which JVM options to forward; which aliases and cache key for a computed classpath; the JVM worker command line. |

Shell:

| Namespace | Responsibility |
|---|---|
| `brisk.shell.worker` | Worker process `-main`: read assignments, require, run, emit events. Must be portable Clojure (no babashka-only libs) so it runs on the JVM too. |
| `brisk.shell.pool` | Start and stop worker processes, pump their output into the event queue. |
| `brisk.shell.fs` | Glob test paths, read files, snapshot modification times. |
| `brisk.shell.runtime` | Detect the parent's runtime, read its classpath and JVM options, run and cache `clojure -Spath`, and build the worker command. Loads on both runtimes. |
| `brisk.shell.jvm` | The one JVM-only call (`RuntimeMXBean` input arguments), loaded through `requiring-resolve` because babashka lacks `java.lang.management`. |
| `brisk.shell.terminal` | TTY detection, width, raw keyboard input, redrawing a frame in place. |
| `brisk.shell.watch` | The watch loop: polls mtimes and keys, drives `brisk.watch` and one engine run at a time. |
| `brisk.main` | `-main`: wire everything together and run the event loop (or hand over to `brisk.shell.watch`). |

## Data contracts

These shapes are the agreement between namespaces. Change them here first,
then in code.

### Normalized config (`brisk.config`)

```clojure
{:tests            [{:id           :unit            ; keyword
                     :type         :kaocha.type/clojure.test
                     :test-paths   ["test"]
                     :source-paths ["src"]
                     :ns-patterns  ["-test$"]       ; regex strings
                     :focus        []               ; ids: ns or ns/var symbols
                     :skip         []
                     :focus-meta   []               ; keywords
                     :skip-meta    []}]
 :fail-fast?       false
 :color?           true
 :capture-output?  true
 :reporter         :live                            ; brisk reporter keyword
 :watch?           false
 :workers          8                                ; brisk-only; default depends on :runtime
 :isolate?         false                            ; brisk-only: fresh process per ns
 :runtime          :bb                              ; brisk-only: :bb or :jvm; default is the parent's runtime
 :warnings         ["unsupported plugin :kaocha.plugin/foo ignored"]
 :errors           []                               ; config Kaocha would exit on; defaults used
 :plugins          [:kaocha.plugin/filter ...]
 :filters          {:focus [] :skip [] :focus-meta [] :skip-meta []}} ; global (CLI) filters
```

`:filters` holds the CLI filters (and top-level `:kaocha.filter/*` keys).
Kaocha applies them as a second pass after each suite's own `:focus`/`:skip`
keys, so they stay separate from the per-suite vectors. Filter entries keep
the type they had: symbols from tests.edn, keywords from the CLI.

Kaocha accepts both `:test-paths` and `:kaocha/test-paths` style keys, and
`#kaocha/v1` fills in defaults. brisk must match Kaocha's normalization. The
reference is Kaocha's own source in
`~/.m2/repository/lambdaisland/kaocha/1.91.1392/kaocha-1.91.1392.jar`
(`kaocha/config.clj`, `kaocha/default_config.edn`, `kaocha/plugin/filter.clj`).

Kaocha options brisk doesn't support produce a warning, never a silent
ignore. Plugins brisk treats as built in or harmless: `:kaocha.plugin/filter`,
`:kaocha.plugin/capture-output`, `:kaocha.plugin/randomize` (warn: ordering
is per worker), `:kaocha.plugin/print-invocations` (no-op),
`:kaocha.plugin/profiling` (brisk always reports slowest tests). Anything
else: warning.

### Namespace info (`brisk.discover`)

```clojure
{:ns       'my.app-test
 :file     "test/my/app_test.clj"
 :requires #{'my.app 'clojure.test}   ; libs that get loaded
 :as-alias-only #{}                  ; `:as-alias` libs, which don't load
 :meta     {:synchronized true}      ; ns name metadata + attr-map
 :vars     {'foo {:slow true}}       ; literal metadata on deftest names
 :test?    true}            ; set by discover/mark-tests: under a test path and matches ns-patterns
```
A file whose ns form can't be read gives `{:file path :error msg}`.

### Parent → worker messages (stdin, one EDN map per line)

```clojure
{:op :run :ns 'my.app-test :run-id 3
 :select {:passes [{:focus [...] :skip [...] :focus-meta [...] :skip-meta [...]
                    :suite-focused? bool}]
          :ns-meta {...}}   ; from brisk.select/plan; workers call select/decide
 :capture-output? true}
{:op :shutdown}
```

### Worker → parent events (stdout)

Each event is one line: the prefix `#brisk/event ` followed by an EDN map.
Any other line on a worker's stdout or stderr is stray output; the parent
attributes it to whatever test that worker is currently running. The parent
adds `:worker` (an integer id) and `:at` (millis) to each event it receives.

```clojure
{:event :worker/ready}
{:event :ns/start      :ns ns :run-id n}
{:event :ns/error      :ns ns :run-id n :error err   ; load or fixture failure
                       :phase (:load | :run)}
{:event :var/start     :ns ns :var sym :file str :line int}
{:event :assertion     :ns ns :var sym :type (:fail | :error)
                       :message str-or-nil :contexts [str]
                       :expected value :actual value
                       :file str :line int :error err-or-nil}
{:event :var/end       :ns ns :var sym :pass int :fail int :error int
                       :duration-ms num :output str-or-nil}
{:event :var/skipped   :ns ns :var sym :reason (:pending | :skip-meta | ...)}
{:event :ns/end        :ns ns :run-id n :duration-ms num}
```

Passing assertions are counted in `:var/end`, not sent one by one.

Execution details (`brisk.protocol`, `brisk.shell.worker`, `brisk.shell.pool`):

- `:ns/error` is always followed by `:ns/end`, so every namespace has exactly
  one terminal event. `:ns/error` covers load errors and a `:once` or `:each`
  fixture that throws.
- `:var/skipped :reason` is whatever `brisk.select/decide` returned.
- The pool turns every line into a flat event: worker events plus `:worker`
  and `:at`; stray lines as `{:event :output :text str :stream :stdout|:stderr}`.
  A line with the `#brisk/event ` prefix whose payload does not parse stays
  an `:output` event and gains `:protocol-error msg`.
- The pool emits `{:event :worker/exit :worker id :code n}` after both
  streams close. When the worker held a namespace, `brisk.schedule/crash-events`
  yields a synthetic `:ns/error` (`:error {:class "brisk.WorkerCrashed" ...
  :exit-code n}`) and `:ns/end` (`:crashed? true`), both with `:synthetic? true`.
- The worker keeps the original stdout for protocol lines and points
  `System/out` and the root `*out*`/`*err*` at stderr, so nothing a test
  prints can corrupt a protocol line. With `:capture-output?` the worker
  points `*out*`, `*err*`, `System/out` and `System/err` at one per-test
  buffer (as `kaocha.plugin.capture-output` does), so what a test prints
  arrives in its `:var/end`. If a test exits the process, a shutdown hook
  sends what it had printed as an `{:event :output}` protocol line, so it
  stays ordered after that test's `:var/start`. Output that escapes capture (other threads,
  `--no-capture-output`) is stray output, attributed to whatever the worker
  is running when the line arrives; ordering between the stdout and stderr
  pumps is not guaranteed, so that attribution is best effort.
- The worker calls the 5-arity `brisk.select/decide` with the loaded
  namespace's real metadata (merged over the static `:ns-meta` copy).
- `:actual` of an `:error` assertion is `{:str "Class: message"}`; the full
  error is in `:error`. Values are cut at 100 items, 8 levels and 10000
  characters; a cut value has no `:edn`.

Values (`:expected`, `:actual`) are sent as
`{:str "(pr-str value)" :edn value-or-absent}`. `:edn` is present only when
the value survives an EDN round trip; `brisk.render` uses it for structural
diffs and falls back to the string. Errors (`err`) are
`{:class str :message str :data str-or-nil :trace [str ...] :cause err-or-nil}`
with the stack trace trimmed of runner frames.

`:capture-output?` means the worker binds `*out*`/`*err*` per test and
returns the text in `:var/end`; the parent shows it only for failed tests.

### Run state (`brisk.run-state`)

A plain map built only by folding events. It must not depend on the
interleaving of events from different workers beyond each worker's own order.
Derived values (`summary`, `exit-code`, `slowest`) are functions of the state,
not stored.

Every message in the parent loop is a map with an `:event` key. Besides the
worker events above, run-state folds:

```clojure
{:event :output :worker id :text str :stream kw}     ; from the pool
{:event :worker/exit :worker id :code n}              ; from the pool, informational
{:event :file/error :file path :error msg}            ; from the engine: unreadable ns form
{:event :run/not-run :namespaces [ns ...]             ; from the engine, at the end
 :reason (:fail-fast | :no-workers | :interrupted)}
```

A crash is counted once, from the scheduler's synthetic `:ns/error`
(class `brisk.WorkerCrashed`); it renders as CRASH. `:worker/exit` never
creates a failure, whatever its code. A file whose ns form cannot be read
counts as one error, as Kaocha's `load-error-testable` does. Namespaces that
never ran are listed in the summary; they count as failures unless the
reason is `:fail-fast`.

### Engine inputs and effects (`brisk.engine`)

Inputs are the flat events from the queue plus `{:event :tick :at ms}`,
which `brisk.main` feeds whenever 80ms pass (the poll timeout, or after an
event if the queue never goes quiet). Effects:

```clojure
{:effect :spawn :worker id}
{:effect :send :worker id :msg {:op :run ...}}
{:effect :stop :worker id}          ; clean shutdown request
{:effect :kill :worker id}          ; 5s after the run, for workers still alive
{:effect :print :lines [str] :stream :out|:err}
{:effect :redraw :frame {:emit [str] :frame [str]}}   ; live mode, on ticks only
{:effect :save-timings :timings {ns ms}}
{:effect :exit :code n}             ; always last; the state is then :done
```

The loop runs in phases: `:running` (the scheduler hands out work),
`:draining` (the scheduler is done; leftovers are folded as
`:run/not-run`, every worker is told to stop, and the engine waits for
their exits, killing them after 5s and giving up 5s later), then `:done`
after the final report. The reporter decides what is shown: `:live`
(redrawn frame, failure report and summary at the end), `:ci` (a line per
finished namespace with its failures, then the summary), `:documentation`
(Kaocha's documentation layout, failures and summary at the end) and
`:result` (`--print-result`: one EDN map from `brisk.run-state/result`).

`preflight` returns Kaocha's exit codes: config errors (including an
unreadable tests.edn) 250, CLI errors 255 (Kaocha's -1), unknown suite 254
(-2), help/version/print-config/print-test-plan 0, and an empty plan prints
Kaocha's "No tests were found" warning and exits 0. A run exits with
`brisk.run-state/exit-code`.

## Watch mode

`brisk.shell.watch` runs a single loop on one thread. Each pass it waits up
to 80ms on the event queue, feeds the event and an 80ms tick to the current
run's `brisk.engine` state, reads pending key presses, and every 200ms
(`brisk.watch/poll-ms`) takes a snapshot of modification times. The queue
still holds only worker events: keys and file changes are read by the loop
that reads the queue, so there is no second queue and no extra thread or
atom. The shell threads one value through the loop (UI state, config,
`{path ns-info}` for every Clojure file under the test and source paths, the
last snapshot, the engine state of the run in progress). Its mutable
objects are the ones a one-shot run has: the queue, the worker handles and
the live frame height.

**Watched files.** Every file under every suite's `:test-paths` and
`:source-paths`, plus tests.edn. Dot directories and editor droppings
(`.#x`, `x~`, `#x#`, `.swp`, `.tmp`) are skipped (`brisk.watch/watched-file?`).

**From files to a rerun.**

1. `brisk.watch/changes` diffs two snapshots into added, modified and
   deleted paths.
2. The shell re-reads and re-parses only the added and modified Clojure
   files (`brisk.discover/ns-info`; `watch/update-infos`) and computes
   `watch/affected-by`: `brisk.deps-graph/affected` over the new graph for
   added and modified files, over the old graph for deleted ones, since the
   new graph no longer knows them. A path the graph cannot place (a
   resource, an unreadable file) affects every test namespace, as in
   `affected`.
3. The shell hands the state machine
   `{:event :files-changed :paths [...] :affected #{ns} :at ms}`. The state
   machine keeps the paths pending and fires one rerun when `debounce-ms`
   (250ms, longer than the poll interval) passes with no new change, so an
   editor's save-and-rename burst, or two consecutive polls that both saw
   part of it, produces one run and loses no change.
4. A change to tests.edn makes the selection "everything, reload the
   config first". The shell re-reads the file, re-normalizes it and
   rebuilds the graph and snapshot; an invalid file keeps the old config
   and prints the error.

**Selections.** The state machine emits `{:effect :start-run :selection s}`
where `s` is `{:all? :failed? :namespaces :reload-config? :ns-pattern
:test-pattern}`. `watch/select-plan` builds the plan: it takes the normal
`brisk.select/plan` (so the config's suites, ns-patterns, `:focus`,
`:skip` and meta filters always apply), then keeps only the namespaces that

- are selected: `:all?`, or `:namespaces` (the affected ones), or with
  `:failed?` the namespaces that failed last time;
- match `:ns-pattern` (`re-find` on the namespace name);
- can hold a var matching `:test-pattern`, judged from the deftests the
  parser sees. Workers also get `:var-pattern` in the select spec and skip
  other vars (`:not-focused`) so generated tests are filtered too. The
  pattern is matched against the var name.

A namespace selected only because it failed last time runs just its failed
vars, through one extra select pass that focuses `ns/var` ids. A namespace
whose failure had no var (load error, crash) runs whole. Every run starts
fresh workers; nothing is reloaded in a live process, so stale state is
impossible.

**Run state across runs.** The UI keeps the failures of the last run per
namespace, replaces them for the namespaces a run covered, and carries the
rest, so PASS means nothing in the project failed, not that the affected
subset passed. Failures of namespaces that no longer exist are dropped.

**Cancel.** When a debounced change (or `a`, `f`, `Enter`, `c`) arrives
during a run, the shell kills the workers by handle, waits for their exit
events so none of their output reaches the next run, and starts the new
run. The new selection is the union of the cancelled run's selection and
the new one, so a cancelled affected-only run does not lose its namespaces.
A change that affects no test namespace does not disturb a run.

**Keys** (raw mode through `brisk.shell.terminal`, restored when the loop
ends, on Ctrl-C, on a crash, and by a shutdown hook; `isig` stays on, so
Ctrl-C still interrupts):

| Key | Action |
|---|---|
| `a` | rerun all namespaces (filters still apply) |
| `f` | rerun the failures; prints a note when there are none |
| `p` / `t` | prompt for a namespace / test-name regex. Enter applies it and reruns, Esc cancels, backspace edits, an empty pattern clears the filter, an invalid regex keeps the prompt open |
| `Enter` | rerun the last selection (while a run is in progress: restart it) |
| `c` | clear filters and rerun |
| `h` | help |
| `q` | cancel any run and quit (exit code 0) |

`p` and `t` are ignored while a run is in progress (the live frame and the
prompt would fight over the screen). Changes that arrive while the prompt
is open wait until it closes. Escape sequences (arrows and the like) are
swallowed. With stdin not a terminal, no keys are read and raw mode is not
entered; file changes still work, and the process stops on SIGTERM or
SIGINT.

After each run the machine prints `PASS  Waiting for file changes...` or
`FAIL  n failures  Waiting for file changes...`, with `press h to show
help, q to quit` when keys are on, and a `Filters:` line when filters are
set.

**Watch effects.** Pure, in the style of the engine's:

```clojure
{:effect :start-run :selection s}
{:effect :cancel-run}
{:effect :print :lines [str]}
{:effect :prompt :label str :buffer str-or-nil}   ; nil closes the prompt
{:effect :quit}
```

Inputs: `{:event :start}`, `{:event :key :char c}`,
`{:event :files-changed ...}`, `{:event :tick :at ms}` and
`{:event :run/finished :outcome o :known #{ns} :empty? bool}`. An empty plan
(nothing affected, or a filter that matches nothing) finishes at once with
`:empty? true` and prints `No test namespaces matched.`

## Behaviour notes

- **Pooling vs isolation.** By default each worker takes namespaces from the
  queue one after another, so shared source namespaces load once per worker.
  `--isolate` starts a fresh process per namespace. The default should be
  chosen by benchmark on real suites.
- **Serial tests.** A namespace with `^:synchronized` (eftest/hawk's marker)
  or `^:brisk/serial` metadata runs after the parallel batch, alone. This
  needs the namespace's metadata, which `brisk.discover` reads from the `ns`
  form statically.
- **JVM mode.** Workers run on the runtime the parent runs on, unless
  `--runtime` (or `:runtime` in tests.edn) says otherwise; a bb parent is
  detected by the `babashka.version` system property.
  * A JVM parent starts `java <defaults> <forwarded options> -cp <its
    classpath + suite test paths> clojure.main -m brisk.shell.worker` with
    its own `java`. The forwarded options are the parent's
    `RuntimeMXBean` input arguments minus things that collide with the
    parent (`brisk.runtime/forward-jvm-args`). `-XX:TieredStopAtLevel=1`
    comes first so a forwarded setting wins.
  * `--runtime jvm` from a bb parent computes the classpath with `clojure
    -Spath -A<aliases>`: `--aliases`, else `:test` if deps.edn defines it,
    else none. It caches the result in `.brisk/classpath-<key>.txt`, where
    the key is a SHA-256 of deps.edn and the aliases; a cache whose jars
    are gone is recomputed. brisk's own source root (found from
    `brisk/shell/worker.clj` on the parent's classpath) is put in front,
    and the aliases' `:jvm-opts` are passed to the workers.
  * Default worker count: CPU count capped at 8 (bb) or 4 (JVM). The JVM
    cap comes from the benchmark below.
  * A JVM run costs about half a second more than Kaocha's in-process
    run (parent JVM plus worker JVM), and each worker loads the project's
    source again, so the engine already starts every worker at once, workers
    are kept across namespaces, and a worker loads `clojure.test` and
    `brisk.select` before it sends `:worker/ready`. `--isolate` is allowed
    with a warning.
- **Ordering.** The scheduler starts known-slow namespaces first when it has
  timings from a previous run (stored by the shell in `.brisk/timings.edn`).
- **Fail fast.** On the first failure, stop handing out work and shut workers
  down; report what finished.
- **Watch mode** (`--watch`, or `:watch? true` in tests.edn) does a full
  run, then stays running. See "Watch mode" below.
- **CLI.** Kaocha's: `[suite-id ...]`, `--focus`, `--skip`, `--focus-meta`,
  `--skip-meta`, `--fail-fast`, `--watch`, `--config-file`, `--[no-]color`,
  `--[no-]capture-output`, `--reporter` (Kaocha reporter names mapped to the
  closest brisk reporter), `--plugin` (warn), `--print-config`,
  `--print-test-plan`. brisk's: `--workers N`, `--isolate`, `--runtime bb|jvm`, `--aliases :a:b`.

## Decisions

Choices made where Kaocha gives no answer or brisk cannot match it
exactly. Each is the conservative option.

- **Overlapping suites.** When two suites select the same namespace,
  Kaocha runs it twice. brisk runs it once, with the first suite's
  selection, because run state is keyed by namespace.
- **Colour.** Kaocha colours output even when piped. brisk colours only
  when stdout is a terminal or `--color` is given explicitly, and never
  when `NO_COLOR` is set, so CI logs stay plain unless asked.
- **Unreadable tests.edn.** Kaocha lets the reader exception escape. brisk
  prints the error and exits 250, the code Kaocha uses for invalid config.
- **Namespaces that never ran.** When no worker can be started, every
  namespace left in the queue counts as one failure, so a broken
  environment can never exit 0. Fail-fast leftovers are listed but not
  counted.
- **Watch mode with no tests at startup** exits 0 with Kaocha's "No tests
  were found" warning instead of waiting for tests to appear.
- **Classpath cache.** The `--runtime jvm` cache key covers deps.edn and
  the aliases only. Edits to a local dependency's deps.edn or to the
  user-level deps.edn go unnoticed until the cache file is deleted.
  Hashing the output of `clojure -Spath` would need running it each time,
  which is the cost the cache avoids.
- **Forwarded JVM options.** The parent's options are copied rather than
  rebuilt from the alias, so `-J` flags and `JAVA_TOOL_OPTIONS` take effect.
  A debugger, JMX port or socket REPL is not copied, since the parent holds
  the port. The heap size is copied as is, so N workers can use N times
  the parent's `-Xmx`.

### JVM benchmark

Wall time in seconds of `clojure -M:test` (Kaocha 1.91.1392) and of
`clojure -M:test:brisk --workers N` on four copies of real projects, three
runs each, on a 16-core Mac that was also busy with other work (load
average 12 to 17), so the spread is wide. Every run had the same executed
vars, assertion counts and pass/fail per var as Kaocha.

| Project (vars) | Kaocha | 1 | 2 | 4 | 8 |
|---|---|---|---|---|---|
| local-coder (23) | 2.2, 2.2, 2.8 | 2.7 | 2.7 | 2.7 | 2.8 to 2.9 |
| hosted-clay (70) | 8.9, 12.2, 13.1 | 9.2 to 9.6 | 8.2 | 8.3 to 8.7 | 11.1 to 11.6 |
| secrets (345) | 110, 112, 127 | | 70 to 71 | 70 to 76 | 72 to 80 |
| reader (244) | 436, 590, 631 | | | 188, 195, 351 | 234, 380, 490 |

More workers than four never helped: 8 workers were slower than 2 or 4 on
hosted-clay and no faster on secrets, because each JVM loads the same
source and the cores were shared. Gains come from overlapping namespaces
(secrets: the one 62-second test sets the floor; reader: embedded Postgres
setup overlaps). For a suite of a few seconds Kaocha's single JVM wins by
the cost of starting a second one.

### Deliberate divergences from Kaocha

`bb test:diff` (`brisk.diff.kaocha-e2e`) runs Kaocha 1.91.1392 under
babashka and brisk on the fixture projects in `dev-resources/projects` and
requires the same executed vars, outcomes, assertion counts and exit
codes. These cases differ on purpose, and the differential tests pin the
difference:

- **A namespace that fails to load.** Kaocha's `kaocha.testable/load`
  rethrows a namespace's load error to the suite, so the whole suite
  becomes one error and none of its tests run. brisk reports that
  namespace (and an unreadable ns form, a `:once` fixture that throws)
  as one error each and runs the rest. Both exit non-zero.
- **Fail-fast.** Kaocha stops at the first failing assertion, even inside
  a namespace. brisk stops handing out namespaces; the ones already
  running (with one worker, just the failing one) finish.
- **Load error class on the JVM.** The JVM wraps a missing-namespace error
  raised while loading a test namespace in a `CompilerException`; babashka
  throws the `FileNotFoundException` itself. brisk reports whatever the
  worker saw, with the cause chain.
- **A test that calls `System/exit`.** It ends Kaocha's process. In brisk
  it ends one worker; the namespace is reported as a CRASH and the run
  goes on.
- **A `:once` fixture that throws.** On babashka Kaocha lets the exception
  escape and exits with a stack trace. brisk reports a namespace error.
- **`^:kaocha/pending` on an ns form.** Kaocha counts the namespace as
  one pending test; brisk counts each of its vars as skipped (reason
  `:pending`). The same vars run (none).

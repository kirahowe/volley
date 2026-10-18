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
| `brisk.junit` | Run state → the Ant JUnit XML report `kaocha.plugin/junit-xml` writes. |
| `brisk.hooks` | Kaocha's hooks plugin and `:brisk/worker-setup` as data: reading them from tests.edn, the `:configure` message, the Kaocha-shaped testables, test plan and result hooks receive. |
| `brisk.runtime` | Which worker runtime and how many workers by default; which JVM options to forward; which aliases and cache key for a computed classpath; the JVM worker command line. |

Shell:

| Namespace | Responsibility |
|---|---|
| `brisk.shell.worker` | Worker process `-main`: read assignments, require, run, emit events. Must be portable Clojure (no babashka-only libs) so it runs on the JVM too. |
| `brisk.shell.hooks` | Resolves and calls hooks inside a worker (and `post-summary` in the parent). Portable, like the worker. |
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
                     :skip?        true             ; only with :kaocha.testable/skip: never runs
                     :type         :kaocha.type/clojure.test
                     :test-paths   ["test"]
                     :source-paths ["src"]
                     :ns-patterns  ["-test$"]       ; regex strings
                     :focus        []               ; ids: ns or ns/var symbols
                     :skip         []
                     :focus-meta   []               ; keywords
                     :skip-meta    []
                     :hooks        {:pre-test [...]}  ; suite-level hooks, when present
                     :worker-setup [...]}]             ; suite-level :brisk/worker-setup, when present
 :fail-fast?       false
 :color?           true
 :capture-output?  true
 :reporter         :live                            ; brisk reporter keyword
 :watch?           false
 :workers          8                                ; brisk-only; default depends on :runtime
 :isolate?         false                            ; brisk-only: fresh process per ns
 :preload?         false                            ; brisk-only: each worker requires every planned ns first
 :bindings         {my.ns/*var* value}              ; Kaocha's :kaocha/bindings, bound in the workers
 :runtime          :bb                              ; brisk-only: :bb or :jvm; default is the parent's runtime
 :hooks            {:pre-load [my.hooks/load!] ...}  ; only with :kaocha.plugin/hooks; see "Hooks"
 :worker-setup     [my.test/isolate!]              ; from :brisk/worker-setup
 :junit-xml        {:target-file "junit.xml"        ; only with :kaocha.plugin/junit-xml and a file
                   :omit-system-out? false :add-location? false}
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
`:kaocha.plugin/profiling` (brisk always reports slowest tests), `:kaocha.plugin/hooks`
(see "Hooks"), `:kaocha.plugin/junit-xml` (`brisk.junit`; the parent writes
the report from the run state after the summary). Anything
else: warning.

Flags that belong to a plugin (`--junit-xml-file`, cloverage's `--cov-*`)
are parsed by `brisk.cli` like any other, but, as in Kaocha, they are an
unknown option (exit 255) unless their plugin is in `:plugins` or given
with `--plugin` (`brisk.cli/plugin-flag-errors`). Flags of a listed plugin
brisk does not run are accepted with a warning.

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
          :ns-meta {...}    ; from brisk.select/plan; workers call select/decide
          :resolve-focus-meta [k ...]        ; only when some focus-meta key is not
          :variants {[k ...] passes-or-:excluded}}  ; visible statically (see below)
 :preload [ns ...]          ; only then (or with --preload): require these first
 :bindings {var-sym value}  ; :kaocha/bindings: bound around loading and running the ns
 :capture-output? true
 :fail-fast? true}          ; only with fail-fast: skip the vars after a failing one
{:op :configure                               ; right after spawn, only when hooks or setup exist
 :hooks {:pre-load [entry] ...}               ; top-level hooks that run in workers
 :worker-setup [entry]
 :suites {:unit {:hooks {...} :worker-setup [...]}}
 :testables {:unit suite-testable}            ; brisk.hooks/suite-testable per suite
 :config kaocha-shaped-config}                ; brisk.hooks/kaocha-config
{:op :shutdown}
```

`:run` messages also carry `:suite`, the id of the suite the namespace was
planned under.

**Tests the parser cannot see.** `brisk.discover` sees only literal
`deftest`, `deftest-` and `defspec` forms, but any macro call can define
tests, so the parent's plan treats every namespace as possibly holding
more tests than it shows, and workers decide per var with the real vars
and metadata:

- A `--focus` on `ns/var` keeps namespace `ns` in the plan even when the
  parser shows no such var; a focus on another namespace's var does not.
- Every namespace may hold a macro-defined test with any metadata, so a
  `--focus-meta` keeps every namespace in the plan.
- Kaocha ignores a focus-meta key that no loaded test carries
  (`remove-missing-metadata-keys`). When some key is carried by no test
  the parser can see, only the loaded tests can tell, so the plan has a
  `:preload` list (every namespace of the suites Kaocha would load) and
  each select spec carries `:resolve-focus-meta` and `:variants`: the
  passes for each set of keys that might turn out unused, computed by
  running the filters once per set (`:excluded` where the namespace has
  nothing left to run). A worker requires every namespace in `:preload`
  before its first test, computes the keys its loaded tests carry
  (`brisk.select/used-meta`), picks the variant
  (`brisk.select/resolve-focus-meta`) and warns, once per worker, about
  each dropped key with Kaocha's message (`:worker/warning`; the parent
  prints each text once).
- A namespace that finishes with every var skipped by a filter (or with
  no vars) is not listed or counted (`brisk.run-state/unselected?`), as
  Kaocha does not report it.

### Worker → parent events (stdout)

Each event is one line: the prefix `#brisk/event ` followed by an EDN map.
Any other line on a worker's stdout or stderr is stray output; the parent
attributes it to whatever test that worker is currently running. The parent
adds `:worker` (an integer id) and `:at` (millis) to each event it receives.

```clojure
{:event :worker/ready}
{:event :ns/start      :ns ns :run-id n}
{:event :ns/error      :ns ns :run-id n :error err   ; setup, load or fixture failure
                       :phase (:setup | :load | :run)}
{:event :var/start     :ns ns :var sym :file str :line int}
{:event :assertion     :ns ns :var sym :type (:fail | :error)
                       :message str-or-nil :contexts [str]
                       :expected value :actual value
                       :file str :line int :error err-or-nil}
{:event :var/end       :ns ns :var sym :pass int :fail int :error int
                       :duration-ms num :output str-or-nil}
{:event :var/skipped   :ns ns :var sym :reason (:pending | :skip-meta | ...)}
{:event :ns/end        :ns ns :run-id n :duration-ms num
                       :pass n}                    ; only when a :once fixture asserted
{:event :worker/error  :phase :shutdown :error err}   ; a shutdown hook threw; one failure
{:event :worker/warning :message str}                ; printed once per run, e.g. an unused focus-meta key
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
  an `:output` event and gains `:protocol-error msg`. It is never dropped:
  run-state counts it as a failure of its own (`:protocol-error`, and the
  namespace the worker held fails), and the engine prints a notice the
  moment it arrives. Because the lost line may have been the `:ns/end`, a
  worker that holds a namespace and sends nothing readable for 10s after
  a protocol error is killed by the engine's watchdog, and its exit is
  reported as a crash that says why.
- The worker encodes every event with the print vars bound to
  `brisk.protocol/safe-print-bindings` (`*print-length*` and
  `*print-level*` nil, `*print-dup*`, `*print-meta*` and
  `*print-namespace-maps*` false, `*print-readably*` true). Events are
  printed inside the test's dynamic scope, and a fixture that binds
  `*print-length*` or a namespace that `set!`s it would otherwise cut
  protocol lines short.
- The pool emits `{:event :worker/exit :worker id :code n}` after the
  process exits and both streams close, waiting at most 500ms for the
  streams: a background process a test started inherits the worker's
  pipes and may keep them open long after the worker is gone. Lines that
  arrive after the exit event are stray output of no namespace. When the worker held a namespace, `brisk.schedule/crash-events`
  yields a synthetic `:ns/error` (`:error {:class "brisk.WorkerCrashed" ...
  :exit-code n}`) and `:ns/end` (`:crashed? true`), both with `:synthetic? true`.
- The worker reads commands from the original stdin and points
  `System/in` (with `System/setIn`) and `*in*` at empty streams, so a test
  that reads stdin gets end of file instead of blocking on the command
  channel.
- The worker keeps the original stdout for protocol lines and points
  `System/out` and the root `*out*`/`*err*` at stderr, so nothing a test
  prints can corrupt a protocol line. With `:capture-output?` the worker
  points `*out*`, `*err*`, `System/out` and `System/err` at one per-test
  buffer (as `kaocha.plugin.capture-output` does), so what a test prints
  arrives in its `:var/end`. If a test exits the process, a shutdown hook
  sends what it had printed as an `{:event :output}` protocol line, so it
  stays ordered after that test's `:var/start`; that event carries
  `:captured? true`. Output that escapes capture (load-time prints,
  `--no-capture-output`, JVM warnings) is stray output: the engine prints
  it as it arrives (on stdout in CI and documentation mode, on stderr with
  `--print-result`, above the frame in live mode), as Kaocha does, and
  run-state attributes it to whatever the worker is running when the line
  arrives, for the failure report; ordering between the stdout and stderr
  pumps is not guaranteed, so that attribution is best effort.
- The worker calls the 5-arity `brisk.select/decide` with the loaded
  namespace's real metadata (merged over the static `:ns-meta` copy).
- `:actual` of an `:error` assertion is `{:str "Class: message"}`; the full
  error is in `:error`.
- A `:fail` whose actual value is a failed `clojure.test.check/quick-check`
  result (what a failing `defspec` reports) also carries `:check {:seed n
  :num-tests n :failing-size n :smallest value :fail value :result value}`
  (`brisk.protocol/encode-check`; `:result` only when the property returned
  something other than false). `brisk.render` shows the seed, the count, the
  shrunk input and a rerun call instead of the raw result map.
- `:file` and `:line` of a `:fail` are the reported ones only when the file
  belongs to the project (`brisk.shell.worker/project-file?`). Otherwise,
  for example an interpreter file that test.check found by walking the
  stack on babashka, they are the test var's own. Values are cut at 100 items, 8 levels and 10000
  characters; a cut value has no `:edn`.

Values (`:expected`, `:actual`) are sent as
`{:str "(pr-str value)" :edn value-or-absent}`. `:edn` is present only when
the value survives an EDN round trip; `brisk.render` uses it for structural
diffs and falls back to the string. Errors (`err`) are
`{:class str :message str :data str-or-nil :trace [str ...] :cause err-or-nil}`
with the stack trace trimmed of runner frames.

On babashka the Java stack trace of an error in a test holds only
interpreter frames, so `brisk.shell.worker/encode-throwable` takes the
trace from `sci.core/stacktrace` instead (`brisk.protocol/interpreter-trace`:
`my.app/parse (src/my/app.clj:12)`, built-in and runner frames dropped).
The interpreter records that call stack only when no interpreted
`try`/`catch` is active where the exception passes, and the worker's own
code is interpreted too, so the worker's catches around test execution are
written `(catch ^:sci/error Throwable e ...)`, which tells SCI to keep the
location. The exception then reaches the worker wrapped in an ExceptionInfo
with `:sci.impl/callstack` in its data; the worker reports the wrapped
cause (its class, message and data) with the wrapper's call stack. The JVM
keeps its own stack trace.

A test var opens (`:var/start`) before its `:each` fixtures run and
closes (`:var/end`) after them, so an assertion in an `:each` fixture
counts for that var, as in Kaocha. An assertion in a `:once` fixture has
`:var nil` and counts for the namespace; the passing ones arrive as
`:pass` in the namespace's `:ns/end`. A namespace with no var to run
(all skipped by the filters, or pending) runs no fixtures, as Kaocha
does not run a namespace whose tests are all skipped.

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
{:event :run/interrupted :namespaces [ns ...]         ; from the engine, fail-fast
 :reason :fail-fast}
```

A var's failure and error counts are the larger of the assertion events
that arrived and the counts in its `:var/end`. When `:var/end` counts
more, the difference is reported as a failure saying the details were
lost, and counts toward the exit code as that many assertions.

A crash is counted once, from the scheduler's synthetic `:ns/error`
(class `brisk.WorkerCrashed`); it renders as CRASH. `:worker/exit` never
creates a failure, whatever its code. A file whose ns form cannot be read
counts as one error, as Kaocha's `load-error-testable` does. Namespaces that
never ran are listed in the summary; they count as failures unless the
reason is `:fail-fast`. A planned namespace that ends the run with no
terminal status and is neither listed as not run nor interrupted is
listed as `:unfinished` and adds one to the exit code: a worker that
vanished without a crash being reported cannot make a run pass. Interrupted namespaces (status `:interrupted`)
keep the vars that finished, lose the one that was running, are listed
in the summary, and never count as failures.

### Engine inputs and effects (`brisk.engine`)

Events from a worker id the run never spawned are ignored.
Inputs are the flat events from the queue plus `{:event :tick :at ms}`,
which `brisk.main` feeds whenever 80ms pass (the poll timeout, or after an
event if the queue never goes quiet). In live mode a tick at most every
250ms also carries `:width`, the terminal width read again, which
replaces the width the live view renders to. Effects:

```clojure
{:effect :spawn :worker id}
{:effect :send :worker id :msg {:op :configure ...}}  ; right after its spawn, when configured
{:effect :send :worker id :msg {:op :run ...}}
{:effect :stop :worker id}          ; clean shutdown request
{:effect :kill :worker id}          ; 5s after the run, for workers still alive
{:effect :print :lines [str] :stream :out|:err}
{:effect :redraw :frame {:emit [str] :frame [str]}}   ; live mode, on ticks only
{:effect :save-timings :timings {ns ms}}
{:effect :write-file :path str :text str}   ; the junit-xml report, after the summary
{:effect :post-summary :hooks [entry] :result kaocha-result}   ; after the summary, with post-summary hooks
{:effect :exit :code n}             ; always last; the state is then :done
```

The loop runs in phases: `:running` (the scheduler hands out work),
`:draining` (the scheduler is done; leftovers are folded as
`:run/not-run`, every worker is told to stop, and the engine waits for
their exits, killing them after 5s and giving up 5s later), then `:done`
after the final report. The reporter decides what is shown: `:live`
(redrawn frame, failure report and summary at the end), `:ci` (a line per
finished namespace with its failures, then the summary; a test that runs
for 30s gets a `still running: ns/var (30.01s)` line, repeated every 30s,
in this mode and the documentation one), `:documentation`
(Kaocha's documentation layout, failures and summary at the end) and
`:result` (`--print-result`: one EDN map from `brisk.run-state/result`).

`preflight` returns Kaocha's exit codes: config errors (including an
unreadable tests.edn) 250, CLI errors 255 (Kaocha's -1), unknown suite 254
(-2), help/version/print-config/print-test-plan 0, and an empty plan prints
Kaocha's "No tests were found" warning and exits 0. A run exits with
`brisk.run-state/exit-code`.

## Hooks

Real suites need setup in the process that runs the tests: pointing the
code at a temporary home directory, instrumenting specs, starting a
system. Kaocha runs everything in one process. brisk's tests run in
several worker processes, so setup has to happen in each of them.

**`:brisk/worker-setup`** (top level or on a suite) is a vector of
qualified symbols naming fns of no arguments. Each worker calls the
top-level ones once, before it loads its first namespace, and a suite's
ones before its first namespace of that suite. It needs no plugin. This
is the recommended way to isolate a process, as relay's
`relay.test-support/isolate!` does.

**Kaocha's hooks plugin** (`:plugins [:kaocha.plugin/hooks]`) is
supported with these semantics (`brisk.hooks/semantics`). A hook is a
qualified symbol, resolved with `requiring-resolve` in the process that
runs it, or a list form, evaluated there. Its arguments are Kaocha's;
`brisk.hooks` builds testables, the test plan and the result in Kaocha's
shape (`:kaocha.testable/type`, `:kaocha.testable/id`, `:kaocha.ns/name`,
`:kaocha.var/var`, `:kaocha.result/pass` and so on). The test plan a
worker passes holds only the namespaces that worker has loaded so far.

| Hook | Runs in | When |
|---|---|---|
| `pre-load` | each worker | once, before its first namespace is required (after `:brisk/worker-setup`) |
| `post-load` | each worker | after every namespace it loads, with the plan so far |
| `pre-run` | each worker | once, before the tests of its first namespace |
| `post-run` | each worker | once, when it is shut down cleanly |
| `wrap-run` | each worker | wraps the run of each namespace (Kaocha also wraps suites and vars) |
| `pre-test` / `post-test` (top level) | each worker | around each namespace and each var; with the suite testable once per worker, before its first namespace of the suite and at shutdown |
| `pre-test` / `post-test` on a suite (and `before`/`after`) | each worker | with the suite testable, once per worker as above |
| `pre-load-test` / `post-load-test` | each worker | around each namespace's require; with the suite testable once per worker |
| `pre-report` | each worker | on every clojure.test report event, before brisk counts it |
| `post-summary` | the parent | once, after the summary is printed |
| `config` | nowhere | warned about: brisk plans in the parent before any hook could change the config |

Return values are threaded from hook to hook as in Kaocha. brisk uses the
result of `pre-report` (the event it counts), of a var's `pre-test` (a
testable with `:kaocha.testable/skip` skips the var, reported with reason
`:pre-test-hook`) and of `wrap-run`; it ignores the others, because the
plan was made in the parent before any worker started. `post-summary`
cannot change the exit code.

Two consequences of running per worker deserve attention. A hook that
starts something with a fixed resource (a port, a file) runs once per
worker, so N workers start N of them. `pre-run` runs before a worker has
loaded more than its first namespace, so spec or malli instrumentation
done there misses what later namespaces define; `post-load` runs after
each load and covers them.

Hooks a suite cannot have in Kaocha (`pre-load` on a suite, say), unknown
hook keys, entries that are neither qualified symbols nor lists, and hook
keys without the plugin are warnings.

**Failures.** If `:brisk/worker-setup`, `pre-load`, `pre-run` or a suite's
setup hooks throw (or a hook does not resolve), the worker runs no tests:
every namespace it is given is reported as an `:ns/error` with phase
`:setup` ("not run: worker setup failed"), so a process that could not be
isolated never runs a test. A load hook that throws is that namespace's
load error, a namespace or var hook that throws is a namespace error
(phase `:run`), and the error names the hook. A `pre-report` hook that
throws is printed to the worker's stderr and the event is counted as it
was. A shutdown hook that throws is sent as `:worker/error` and counts as
one failure.
In `--isolate` mode every namespace has its own worker, so every
once-per-worker hook runs once per namespace. A worker killed by
fail-fast or a watch cancel runs no shutdown hooks.

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
   rebuilds the graph, the snapshot and the worker command (whose
   classpath holds the suites' test paths, on either runtime); an invalid
   file, or a JVM classpath that cannot be computed, keeps the old config
   and prints the error. deps.edn is not watched: after changing it,
   restart watch mode.

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
run. Worker ids count up for the life of the parent process (each run's
`brisk.engine/init` gets `:first-worker-id`, the next id after the last
run's), and the engine drops every event from a worker its run did not
spawn, so a cancelled run's worker that takes longer than the 5s wait to
exit can never be mistaken for one of the new run's. The new selection is the union of the cancelled run's selection and
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
- **Fail fast.** On the first failure (a failed or erroring assertion, a
  namespace error or a crash) the engine stops handing out work, kills every
  other busy worker by handle at once (`brisk.schedule/interrupt` takes
  their namespaces back, so their exit is not a crash), drops anything
  else those workers still send, and records their namespaces with
  `:run/interrupted`. The failing worker gets `:fail-fast?` in its run
  message and runs no var after the failing one, so its namespace ends
  right away. The summary lists "Interrupted (stopped by fail-fast)" and
  "Not run (stopped by fail-fast)"; neither counts as a failure, and the
  exit code is the failure's.
- **Watch mode** (`--watch`, or `:watch? true` in tests.edn) does a full
  run, then stays running. See "Watch mode" below.
- **CLI.** Kaocha's: `[suite-id ...]`, `--focus`, `--skip`, `--focus-meta`,
  `--skip-meta`, `--fail-fast`, `--watch`, `--config-file`, `--[no-]color`,
  `--[no-]capture-output`, `--reporter` (Kaocha reporter names mapped to the
  closest brisk reporter), `--plugin` (warn), `--print-config`,
  `--print-test-plan`. brisk's: `--workers N`, `--isolate`, `--preload`, `--runtime bb|jvm`, `--aliases :a:b`.

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
- **Fail-fast inside a var.** Kaocha's `kaocha.report/fail-fast` throws
  at the first failing assertion, so the rest of that var does not run.
  brisk finishes the failing var (its other assertions still count), then
  runs no further var, as Kaocha's `run-testables` does. Namespaces other
  workers were running are killed and listed as interrupted; Kaocha, with
  one process, has nothing to compare them to.
- **Load error class on the JVM.** The JVM wraps a missing-namespace error
  raised while loading a test namespace in a `CompilerException`; babashka
  throws the `FileNotFoundException` itself. brisk reports whatever the
  worker saw, with the cause chain.
- **A test that calls `System/exit`.** It ends Kaocha's process, with
  whatever code the test chose, so `(System/exit 0)` makes Kaocha pass.
  In brisk it ends one worker; the namespace is reported as a CRASH,
  whatever the code, and the run goes on.
- **A `:once` fixture that throws.** On babashka Kaocha lets the exception
  escape and exits with a stack trace. brisk reports a namespace error.
- **An assertion in a `:once` fixture.** Kaocha prints a failing one
  but leaves it out of its counts and the exit code, since its totals
  add up the vars. brisk counts it for the namespace, in the summary and
  in the exit code. (Assertions in an `:each` fixture count for the test
  they wrap in both.)
- **An `:each` fixture that throws.** Kaocha lets the exception end the
  run. brisk reports a namespace error and lists the tests the fixture
  kept from running as skipped (reason `:fixture-failed`).
- **Load order.** Kaocha loads every test namespace before it runs any,
  so a test namespace can rely on something another one defines without
  requiring it (an `assert-expr` method, a spec, a multimethod). brisk
  loads a namespace in the worker that runs it, so such a dependency holds
  only when both happen to land in the same worker in the right order.
  `--preload` (or `:preload? true` in tests.edn) makes each worker require
  every planned namespace before running any, as Kaocha does, at the cost
  of loading everything once per worker.
- **`^:kaocha/pending` on an ns form.** Kaocha counts the namespace as
  one pending test; brisk counts each of its vars as skipped (reason
  `:pending`). The same vars run (none).

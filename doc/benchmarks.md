# Benchmarks

These are the measurements recorded so far. They are small and rough.
Treat them as indications, not a controlled comparison.

## Conditions

These hold for every section up to "JVM, 2026-10-19", which lists its own.

- Machine: a 16-core Mac.
- The machine was shared and loaded during every run. The load average was
  12 to 17 for the JVM runs, so the spread between runs is wide.
- Wall time of the whole command, including process startup.
- No result was discarded. Where three runs exist, all three are listed.

## babashka

| Project | Tests | Own runner | volley |
|---|---|---|---|
| compositor | 26 | 0.50 to 0.65s | 0.43 to 0.50s |
| relay, without `deploy-test` | 507 | 153.8s (relay's own runner) | 27.5s (8 workers) |

On relay, both runners reported the same test counts and the same failures.

On a fixture project in this repository, 4 workers finished 3.7 times
faster than 1 worker.

A fresh run of compositor on 2026-10-18 with volley took 0.15 to 0.52s
(`time`, two runs, load average 9 to 13), which falls inside the range above.

## JVM

Wall time in seconds of `clojure -M:test` (Kaocha 1.91.1392) and of
`clojure -M:test:volley --workers N` on four copies of real projects, three
runs each. Every run had the same executed vars, assertion counts and
pass/fail per var as Kaocha.

| Project (vars) | Kaocha | 1 worker | 2 | 4 | 8 |
|---|---|---|---|---|---|
| a small app (23) | 2.2, 2.2, 2.8 | 2.7 | 2.7 | 2.7 | 2.8 to 2.9 |
| hosted-clay (70) | 8.9, 12.2, 13.1 | 9.2 to 9.6 | 8.2 | 8.3 to 8.7 | 11.1 to 11.6 |
| secrets (345) | 110, 112, 127 | | 70 to 71 | 70 to 76 | 72 to 80 |
| reader (244) | 436, 590, 631 | | | 188, 195, 351 | 234, 380, 490 |

Empty cells were not measured.

### Adoption test, 2026-10-18

Copies of two of the projects above switched from Kaocha to volley the way
the README describes, one run each.
The machine was loaded: the load average was 7 to 20 during these runs,
higher than for the table above.

| Project (tests) | Kaocha | volley |
|---|---|---|
| hosted-clay (70) | 6.3, 9.8 | 9.3 |
| reader (244, 1440 assertions) | 215 | 40 |

Both runners reported the same tests and assertions with no failures on
both projects. The reader numbers are lower than in the table above for
both runners; the two sets were taken on different days under different
load, so compare within a row, not across tables.

## JVM, 2026-10-19

Before and after volley#2 (short JVM suites in the parent's JVM) and
volley#12 (hook support loaded only when configured).

### Method

- Copies of hosted-clay, reader and secrets in a temporary directory,
  without `.git`, `.jj`, `.cpcache`, `target`, `node_modules` and
  `.volley`. Each copy's `deps.edn` got three aliases: Kaocha 1.91.1392
  (`clojure -M:test:kaocha`), volley at d4e3ef2, before the change
  (`-M:test:vbase`), and volley with the change (`-M:test:vnew`). The
  volley aliases override the `:test` alias's volley with a
  `:local/root`.
- The parent runs on the JVM, so the workers are JVM processes.
- Wall time of the whole `clojure` command, from a babashka script that
  runs the commands one after another and records every run. "Back to
  back" means five runs of one command, then five of the next.
  "Alternating" means the two commands take turns, in the order ABBA.
- Before the measured runs of volley with the change, one run that
  writes `.volley/history.edn` and `.volley/ns-infos.edn`, as any earlier
  run on a developer's machine does. Without them the first run starts
  worker JVMs as before: 9.1s on hosted-clay.
- In the secrets copy, `init_test.clj` and `coordinate_test.clj` were
  moved out of `test/`. They need a git checkout, and when a namespace
  fails to load, Kaocha fails the whole suite. Both runners then reported
  322 tests and the same two failing tests (three failed assertions),
  which check the copy's `deps.edn` aliases and a copied rendered book.
  Both runners reported the same counts as each other on all three
  projects.
- No other agent ran. Other processes still kept three to four of the 16
  cores busy (two `yes` processes, Finder, BTLEServer): the load average
  was 8 to 10 during the hosted-clay runs and up to 19 during reader.

### hosted-clay (70 tests, 320 assertions)

Back to back, five runs each:

| | Kaocha | volley before | volley after |
|---|---|---|---|
| median | 6.19s | 9.86s | 6.13s |
| runs | 6.46, 6.17, 6.19, 6.15, 6.21 | 9.72, 9.97, 9.71, 9.86, 9.99 | 6.28, 6.09, 6.26, 6.10, 6.13 |

Alternating with Kaocha, seven rounds: Kaocha 6.29s median (6.26 to
6.37), volley after 6.24s (6.21 to 6.37).

Where the time went before the change. Every worker JVM loads the app,
and the first test in each worker also starts its lazy setup, about 5s
per worker; four workers did that at once. With one worker the run took
9.0s. On top of the tests, the parent JVM took 1.2s from its start to
loading volley, a worker took 0.44s from spawn to ready, and its exit
took another 0.3s.

After the change the run stays in the parent's JVM (`volley.runtime/worker-plan`).
The parent's own load, measured per file, went from 0.81s to 0.58s: on a
run where no test file changed it no longer loads the parser (edamame and
tools.reader, about 0.35s), and it loads watch mode and `clojure.pprint`
only when it needs them. hosted-clay gets part of that back because the
app loads tools.reader itself.

### hosted-clay after the merge

The same measurement on the merged code with the follow-up fixes (the
worker's stop flag per command loop, the serial-time check in
`worker-plan`), later on 2026-10-19. A fresh copy as above, with
`--runtime jvm` passed to volley. Two warm-up runs from an empty
`.volley/`: the first started worker JVMs (9.9s), the second went in
process (6.3s). Then five runs of each, back to back. The same three
to four cores were busy with other processes, the load average 8 to 9,
and no other test run or agent was running.

| | Kaocha | volley |
|---|---|---|
| median | 6.16s | 6.14s |
| runs | 6.15, 6.16, 6.15, 6.20, 6.25 | 6.14, 6.14, 6.17, 6.10, 6.20 |

Both reported 70 tests and 320 assertions with no failures.

The timings of the first run, with four worker JVMs, add up to 27.8s,
because each worker pays the app's lazy setup in its first test. In
process the run takes about 6s, so only a run in process can show how
long one takes.

### Large suites

| Project | Kaocha (one run) | volley before | volley after |
|---|---|---|---|
| reader (244 tests, 1440 assertions) | 103.9s | 40.3, 40.5, 43.4s | 38.6, 39.7, 40.6s |
| secrets (322 tests, 3455 assertions) | 104.1s | 69.8, 69.9s | 69.5, 69.6s |

volley before and after alternated. Their recent runs take more than 10
seconds, so both projects keep four worker JVMs and their gains.

### JIT flag

Workers start with `-XX:TieredStopAtLevel=1`. Medians of three runs of
volley before the change, with the flag and without it
(`-J-XX:TieredStopAtLevel=4`, which the workers then inherit), and of
Kaocha with the flag added and without:

| Run | C1 only | full JIT |
|---|---|---|
| hosted-clay, 1 worker | 8.98s | 7.83s |
| hosted-clay, 4 workers | 9.66s | 10.45s |
| reader, 4 workers (two runs) | 39.7s | 39.7s |
| secrets, 4 workers (two runs) | 69.9s | 70.9s |
| Kaocha on hosted-clay, one JVM | 8.18s | 6.36s |

With one JVM the full JIT wins; with four, the C2 compiler threads compete
for the cores. The flag stays: it is applied only to worker processes, and
an in-process run uses the parent's own JVM settings.

### Worker start-to-ready (volley#12)

Time from spawning a JVM worker on the hosted-clay classpath to its
`:worker/ready` line, 15 runs of each variant, alternating:

| JVM options | before | after |
|---|---|---|
| `-XX:TieredStopAtLevel=1` (the default) | 428ms | 408ms |
| none | 495ms | 473ms |

A worker that gets no `:configure` message no longer loads
`volley.shell.hooks`, which saves about 20ms per JVM worker.

## Conclusions

- On small suites volley gains little. On a short JVM suite volley used
  to be slower than Kaocha, because the parent and every worker each
  start a JVM and load the project: hosted-clay took 9.9s against
  Kaocha's 6.2s. Since volley#2, a JVM run whose recent runs took at most
  10 seconds runs in the parent's JVM and takes about as long as Kaocha
  (6.1s). The first run in a checkout, or in CI without a cached
  `.volley/`, still starts worker JVMs.
- Gains come from overlapping namespaces that take a long time. In secrets
  the one 62-second test sets the floor; in reader, embedded Postgres setup
  in several namespaces overlaps.
- More than four JVM workers did not help. Each JVM loads the same source,
  and the cores were shared. On hosted-clay 8 workers were slower than 2
  or 4, which is why the default is capped at 4 on the JVM.
- On babashka, startup is cheap, so even a 26-test suite comes out about
  even with a plain `clojure.test` runner.

## Not measured

- No runs on an idle machine. The quietest ones, on 2026-10-19, still had
  three to four cores busy with other processes.
- The 23-test app since volley#2.
- No comparison with Kaocha on babashka.
- No memory measurements. N JVM workers can use N times the parent's
  `-Xmx`.

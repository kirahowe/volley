# Benchmarks

These are the measurements recorded so far. They are small and rough.
Treat them as indications, not a controlled comparison.

## Conditions

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
| local-coder (23) | 2.2, 2.2, 2.8 | 2.7 | 2.7 | 2.7 | 2.8 to 2.9 |
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

## Conclusions

- On small suites volley gains little. On a short JVM suite it is slower
  than Kaocha by about half a second, because the parent and a worker each
  start a JVM. local-coder is the example: 2.2 to 2.8s with Kaocha, 2.7s
  with volley. A suite that takes Kaocha under about 10 seconds may be no
  faster: hosted-clay took 9.3s with volley in the adoption test against
  6.3 and 9.8s with Kaocha.
- Gains come from overlapping namespaces that take a long time. In secrets
  the one 62-second test sets the floor; in reader, embedded Postgres setup
  in several namespaces overlaps.
- More than four JVM workers did not help. Each JVM loads the same source,
  and the cores were shared. On hosted-clay 8 workers were slower than 2
  or 4, which is why the default is capped at 4 on the JVM.
- On babashka, startup is cheap, so even a 26-test suite comes out about
  even with a plain `clojure.test` runner.

## Not measured

- No quiet-machine runs.
- No comparison with Kaocha on babashka.
- No memory measurements. N JVM workers can use N times the parent's
  `-Xmx`.

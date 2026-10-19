# Run :once fixtures for namespaces whose vars are all pending

## Context

When every var in a namespace is `:kaocha/pending`, volley runs no fixtures for it. Kaocha still runs the namespace's `:once` fixtures. A fixture with side effects (or one that fails) behaves differently. Noted as a remaining gap by the QA fix pass on 2026-10-18.

## Acceptance criteria

- [ ] Matches Kaocha: `:once` fixtures run for an all-pending namespace (check `kaocha.type.ns` to confirm).
- [ ] Added to the Kaocha diff tests (`bb test:diff`).

## Log

- 2026-10-18: the worker now runs a namespace whose vars are all pending,
  so its `:once` fixtures run as in Kaocha; a `^:kaocha/pending` namespace
  still runs none. The decision is the pure `filters/runs-namespace?`, with
  a defspec. Worker tests cover the fixtures, including a throwing `:once`
  fixture, and the new `pending-fixtures` project is a Kaocha diff case.
  doc/design.md updated.

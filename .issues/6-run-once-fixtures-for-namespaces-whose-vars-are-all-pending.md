# Run :once fixtures for namespaces whose vars are all pending

## Context

When every var in a namespace is `:kaocha/pending`, volley runs no fixtures for it. Kaocha still runs the namespace's `:once` fixtures. A fixture with side effects (or one that fails) behaves differently. Noted as a remaining gap by the QA fix pass on 2026-10-18.

## Acceptance criteria

- [ ] Matches Kaocha: `:once` fixtures run for an all-pending namespace (check `kaocha.type.ns` to confirm).
- [ ] Added to the Kaocha diff tests (`bb test:diff`).

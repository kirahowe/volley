# Exit 249 when a plugin namespace defines no plugin

## Context

If a plugin listed in tests.edn resolves to a namespace that exists but does not define a plugin, Kaocha exits 249. volley accepts it with a warning. Noted by the QA fix pass on 2026-10-18.

## Acceptance criteria

- [ ] volley exits 249 with Kaocha's message in that case.
- [ ] config test plus a Kaocha diff case.

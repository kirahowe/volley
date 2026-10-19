# Allow an Unreleased section in CHANGELOG.md

## Context

version_test's one-version-everywhere reads the version from the first `## [...]` heading, so main can't carry an `## [Unreleased]` section between releases. Wave-1 changes (volley#1, 4, 5, 6, 10, 11) have no changelog entries yet for this reason.

## Acceptance criteria

- [ ] version_test skips an Unreleased heading.
- [ ] Unreleased entries added for the wave-1 changes.

# Ignore the .volley/ cache directory automatically

## Context

volley writes `.volley/timings.edn` (and `.volley/classpath-*.txt` for
`--runtime jvm`) in the project root on the first run. Every project
switched to volley during dogfooding (reader, hosted-clay, secrets,
compositor, relay) needed a manual `.gitignore` entry. In relay, a jj
repo, the timings file was snapshotted into the working copy before the
entry existed and had to be untracked with `jj file untrack`.

pytest avoids this by writing a `.gitignore` containing `*` inside its
cache directory when it creates it.

## Acceptance criteria

- [ ] When volley creates `.volley/`, it also writes `.volley/.gitignore`
      containing `*`, unless that file already exists.
- [ ] A fresh project shows no untracked `.volley/` in `git status` or
      `jj st` after a run.
- [ ] The README's note asking users to add `.volley/` to `.gitignore` is
      removed or reworded.
- [ ] Shell test (`volley.shell.fs`) covering creation and not overwriting
      an existing file.

## Log

- 2026-10-18: Added `volley.shell.fs/ensure-cache-dir!`, which writes a
  `.gitignore` of `*` when it creates `.volley/`; the timings and JVM
  classpath writers both call it. Covered by `test/volley/shell/fs_test.clj`,
  reworded the README note and mentioned it in doc/design.md. A `.volley/`
  left by an older version gets no ignore file, matching pytest.

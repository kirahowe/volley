# Publish the GitHub repo and push the v0.1.0-alpha.1 tag

## Context

0.1.0-alpha.1 is on Clojars as `com.kirahowe/volley` (deployed
2026-10-18 from commit `2517105`, tagged `v0.1.0-alpha.1` locally). The
jar's pom, the CHANGELOG link and the README all point at
`https://github.com/kirahowe/volley`, which does not exist yet, so those
links return 404. Creating the public repo needs Kira's go-ahead.

## Acceptance criteria

- [ ] Public repo `kirahowe/volley` exists with the description used on
      Clojars.
- [ ] `main` and the `v0.1.0-alpha.1` tag are pushed.
- [ ] A GitHub release for the tag carries the CHANGELOG entry.
- [ ] The pom's scm link, the CHANGELOG link and the README's git-dep
      snippet resolve.

## Plan

```
gh repo create kirahowe/volley --public --source . --remote origin --push
git push origin v0.1.0-alpha.1
gh release create v0.1.0-alpha.1 --title "volley 0.1.0-alpha.1" --notes-file <CHANGELOG section>
```

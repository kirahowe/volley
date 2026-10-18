# Load hook support in workers only when hooks are configured

## Context

The worker always loads the hooks support namespaces even when no hooks are configured. The QA fix pass measured about 30ms per JVM worker for this and judged it not worth the indirection at about 12 call sites. Low priority; revisit together with volley#2.

## Acceptance criteria

- [ ] Workers load hook support only after a `:configure` message that carries hooks.
- [ ] JVM worker start-to-ready measured before and after.

# Documentation Tools

Small, dependency-free tools for `docs`.

| Tool | Purpose | Invocation |
|---|---|---|
| [`check-docs.mjs`](./check-docs.mjs) | Checks relative links, tables, corruption markers and spec index coverage | `node docs/tools/check-docs.mjs` |
| [`coupling-analysis.mjs`](./coupling-analysis.mjs) | Coupling and hub analysis for the reference analysis | `node docs/tools/coupling-analysis.mjs` |

## check-docs

The check runs without external dependencies and is suitable for CI:

```bash
node docs/tools/check-docs.mjs
```

It checks:

- relative Markdown links to existing files,
- anchors in linked Markdown files (as a warning),
- tables with consistent column counts,
- accidental chat/tool special tokens (e.g. with `end` or `start`),
- whether spec files are mentioned in the respective README (as a warning).

Exit code `1` on errors, `0` on a clean run. Warnings do not fail the run,
but should be cleaned up when possible.

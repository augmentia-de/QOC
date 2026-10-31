# Documentation Status

> **Status:** 2026-10-31  
> **Reference:** QOC Java engine under `qoc/`.  
> **Purpose:** Quick overview of which documents are specification, implementation
> or working material. The status is a documentation statement, not a substitute
> for build and test results.

## Status legend

| Status | Meaning |
|---|---|
| `implemented` | Spec is implemented in code; build/tests remain authoritative |
| `partial` | Parts are implemented or coverage is not fully verified |
| `spec-only` | Specification exists, implementation still missing |
| `reference` | Analysis/reference material, not an operative spec |
| `working` | Working catalog or decision register, no commitment |

## Operative specification

| Area | Document | Status | Note |
|---|---|---|---|
| Session engine | [`spec/session-engine/`](./spec/session-engine/README.md) | `implemented` | M0–M14 per [`ROADMAP.md`](./ROADMAP.md); build remains authoritative |
| Tools – base families | [`spec/tools/`](./spec/tools/README.md) | `implemented` | Filesystem, Bash, MCP |
| Tools – Subagent | [`spec/tools/07-subagent.md`](./spec/tools/07-subagent.md) | `implemented` | `SubagentTool`/`SubagentRunner` available |
| Tools – Remote Shell | [`spec/tools/08-remote-shell.md`](./spec/tools/08-remote-shell.md) | `spec-only` | `download`/`upload` incl. recursive copy not yet implemented |
| Permissions | [`spec/permissions/`](./spec/permissions/README.md) | `implemented` | Rule chain and effect matrix |
| Provider | [`spec/provider/`](./spec/provider/README.md) | `implemented` | Classification, budget, cache, event stream |
| Persistence | [`spec/persistence/`](./spec/persistence/README.md) | `implemented` | SQLite/PostgreSQL adapter |
| Operations | [`spec/betrieb/`](./spec/betrieb/README.md) | `implemented` | HTTP/SSE, skills, credentials |
| Quality | [`spec/qualitaet/`](./spec/qualitaet/README.md) | `partial` | ArchUnit/JaCoCo gates to verify before commit |
| Agents | [`spec/agents/`](./spec/agents/README.md) | `implemented` | Catalog, tool restrictions, MCP scoping |
| Optimization | [`improve.md`](./improve.md) | `working` | Working catalog, no obligation |
| Diagrams | [`diagrams/`](./diagrams/README.md) | `reference` | Architecture and data flow diagrams |

## Extensions (over OpenCode)

| Feature | Document | Status | Note |
|---|---|---|---|
| OC-plus overview | [`OC-plus.md`](./OC-plus.md) | `reference` | Erweiterungen gegenüber OpenCode |
| Enterprise readiness | [`Enterprise.md`](./Enterprise.md) | `working` | Vier Säulen + Umsetzungsplan |
| Async approvals | [`plans/async-approval.md`](./plans/async-approval.md) | `partial` | Phase 1+2+3 umgesetzt; suspend/resume + Tool-Result-Cache live |
| Skills | [`Skill_loading.md`](./Skill_loading.md) | `implemented` | BM25, per-agent allow/deny/prompt |
| A2A | [`guides/a2a.md`](./guides/a2a.md) | `implemented` | Client + opt-in Server |
| Webhook Guardrail | [`webhook.md`](./webhook.md) | `partial` | Spezifikation + Service |

## Cross-cutting

| Document | Purpose |
|---|---|
| [`INDEX.md`](./INDEX.md) | Role-based entry |
| [`spec/CONVENTIONS.md`](./spec/CONVENTIONS.md) | Writing and structure rules for specs |
| [`spec/INVARIANTS.md`](./spec/INVARIANTS.md) | Register of invariant prefixes and sources |
| [`open-questions.md`](./open-questions.md) | Decision register (mostly decided) |
| [`ROADMAP.md`](./ROADMAP.md) | Implementation order M0–M14 |

## Maintenance rule

When changing any spec or milestone:

1. Check status in this file.
2. Update affected README indices.
3. Run `node docs/tools/check-docs.mjs`.
4. For code changes, additionally run `mvn test` in `qoc/`.

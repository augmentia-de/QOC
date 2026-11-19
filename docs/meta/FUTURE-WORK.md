# Future Work – Planned and Not-Yet-Implemented Parts

> **Status:** 2026-11-04
> **Purpose:** Central overview of all planned, spec-only, or partial parts.
> `STATUS.md` remains the operational status source (what is implemented);
> this file is the planning one (what is planned).

## Spec-only (not yet implemented)

| Area | Document | Description |
|---|---|---|
| Remote Shell | `spec/tools/08-remote-shell.md` | SSH exec/read/write/list/stat + download/upload |
| AWS Lambda Serverless | `deployment/05-aws-lambda-serverless.md` | Serverless mode with agent sync |
| Server/Serverless modes | `guides/deployment-modes.md` | Two modes + MCP tool `qoc_run_agent_with_config` |
| OIDC/Auth0 | `spec/betrieb/04-oidc-auth0.md` | Auth0 as OIDC provider |
| Quality (ArchUnit/JaCoCo) | `spec/qualitaet/` | Gates still to be verified |

## Partial (partially implemented)

| Area | Document | Description |
|---|---|---|
| Quality | `spec/qualitaet/README.md` | ArchUnit/JaCoCo gates |
| Async Approvals | `plans/async-approval.md` | Phases 1+2+3 implemented; suspend/resume live |
| Webhook Guardrail | `webhook.md` | Specification + service |
| GCP Auth0 | `deployment/03-gcp-auth0.md` | Auth0 on GCP (partial) |
| A2A Server | `guides/a2a.md` | Opt-in server |

## Planned Milestones (M15+)

| # | Deliverable |
|---|---|
| M15 | Agent-specific config (Tools, Permissions, MCP, Model, maxSteps) |
| M16 | Agent-scoped tool snapshot and permission rules |
| M17 | MCP server subsets per agent |
| M18 | Multi-agent workflow handoff |

## Open L-Items

| ID | Description | Status |
|---|---|---|
| L-1 | Markdown agent files (`agent/<id>.md`) | decided, not implemented |
| L-6 | Command macros (`command/<id>.md` with `$ARGUMENTS`) | decided, not implemented |
| L-7 | Model catalog snapshot and refresh | decided, not implemented |
| Persistent PTY | Across calls | open |

## Planned Features (from Guides)

| Feature | Document | Description |
|---|---|---|
| Server Mode (VM) | `guides/deployment-modes.md` | Agents locally, admin creates new ones |
| Serverless Mode (Lambda) | `guides/deployment-modes.md` | Agents loaded from server, OIDC |
| MCP tool `qoc_run_agent_with_config` | `guides/deployment-modes.md` | Stateless subagent run with config |
| Remote Shell | `spec/tools/08-remote-shell.md` | SSH-based remote operations |

## Planned Documentation

| Document | Description |
|---|---|
| `spec/betrieb/04-oidc-auth0.md` | Auth0/OIDC spec (spec-only) |
| `spec/tools/10-mcp-serverless.md` | MCP server for serverless (optional) |

# Future Work – Geplante und nicht implementierte Teile

> **Status:** 2026-11-04
> **Zweck:** Zentrale Übersicht aller geplanten, spec-only oder partial
> Teile. `STATUS.md` bleibt die operative Statusquelle (was ist implementiert);
> diese Datei ist die planerische (was ist geplant).

## Spec-only (noch nicht implementiert)

| Bereich | Dokument | Beschreibung |
|---|---|---|
| Remote Shell | `spec/tools/08-remote-shell.md` | SSH exec/read/write/list/stat + download/upload |
| AWS Lambda Serverless | `deployment/05-aws-lambda-serverless.md` | Serverless Mode mit Agent-Sync |
| Server/Serverless-Modi | `guides/deployment-modes.md` | Zwei Modi + MCP-Tool `qoc_run_agent_with_config` |
| OIDC/Auth0 | `spec/betrieb/04-oidc-auth0.md` | Auth0 als OIDC-Provider |
| Quality (ArchUnit/JaCoCo) | `spec/qualitaet/` | Gates noch zu verifizieren |

## Partial (teilweise implementiert)

| Bereich | Dokument | Beschreibung |
|---|---|---|
| Quality | `spec/qualitaet/README.md` | ArchUnit/JaCoCo Gates |
| Async Approvals | `plans/async-approval.md` | Phase 1+2+3 umgesetzt; suspend/resume live |
| Webhook Guardrail | `webhook.md` | Spezifikation + Service |
| GCP Auth0 | `deployment/03-gcp-auth0.md` | Auth0 auf GCP (teilweise) |
| A2A Server | `guides/a2a.md` | Opt-in Server |

## Geplante Meilensteine (M15+)

| # | Deliverable |
|---|---|
| M15 | Agent-spezifische Config (Tools, Permissions, MCP, Model, maxSteps) |
| M16 | Agent-scoped Tool Snapshot und Permission Rules |
| M17 | MCP Server Subsets pro Agent |
| M18 | Multi-Agent Workflow Handoff |

## Offene L-Items

| ID | Beschreibung | Status |
|---|---|---|
| L-1 | Markdown Agent Files (`agent/<id>.md`) | entschieden, nicht implementiert |
| L-6 | Command Macros (`command/<id>.md` mit `$ARGUMENTS`) | entschieden, nicht implementiert |
| L-7 | Model Catalog Snapshot und Refresh | entschieden, nicht implementiert |
| Persistent PTY | Über Aufrufe hinweg | offen |

## Geplante Features (aus Guides)

| Feature | Dokument | Beschreibung |
|---|---|---|
| Server Mode (VM) | `guides/deployment-modes.md` | Agenten lokal, Admin erstellt neue |
| Serverless Mode (Lambda) | `guides/deployment-modes.md` | Agenten vom Server laden, OIDC |
| MCP-Tool `qoc_run_agent_with_config` | `guides/deployment-modes.md` | Stateless Subagent-Run mit Config |
| Remote Shell | `spec/tools/08-remote-shell.md` | SSH-basierte Remote-Operationen |

## Geplante Dokumentation

| Dokument | Beschreibung |
|---|---|
| `spec/betrieb/04-oidc-auth0.md` | Auth0/OIDC-Spec (spec-only) |
| `spec/tools/10-mcp-serverless.md` | MCP-Server für Serverless (optional) |

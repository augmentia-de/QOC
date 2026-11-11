# Subagent-Delegation – Worker-Pool, Routing und Cloud-Topologien

Status: Design-/Entscheidungsdokument (nicht implementiert)
Datum: 2026-11-04

Dieses Dokument beschreibt, wie der Orchestrator Teilaufgaben über das
bestehende `subagent`-Tool an lokale, Serverless- und Server-Worker delegiert –
inklusive Worker-Pool, Routing, Tool-Oberfläche, MCP-Proxy-Varianten und
Cloud-Topologien (GCP/AWS).

## 1. Ziel

Der Orchestrator (Server Mode) soll unabhängige Teilaufgaben über **ein
einziges Tool** (`subagent`) an einen Pool aus Worker-Runtimes delegieren
können:

- **Serverless-Worker** für parallele, stateless I/O-Aufgaben
  (Websearch, Webfetch, MCP, Batch).
- **Server-Worker** für Aufgaben mit gemeinsamem, persistentem Workspace.
- **Lokale Subagents** für Workspace-gebundene Aufgaben im Orchestrator.

Der Pool kümmert sich um Auswahl, Lastverteilung, Health, Retry, Failover,
Timeouts und Kosten-/Token-Aggregation.

## 2. Nicht-Ziele

- Kein neuer zentraler Broker-Dienst.
- Keine beliebigen, vom Modell gelieferten Worker-URLs (kein SSRF).
- Kein Ersatz für A2A-Interoperabilität mit externen Agenten.
- Kein Shared State zwischen Serverless-Workern.
- Kein dynamisches Autoscaling von Server-Workern im MVP.

## 3. Begriffe

| Begriff | Bedeutung |
|---|---|
| **Worker** | QOC-Runtime, die `qoc_run_agent` über `/mcp` anbietet |
| **Worker-Registry** | serverseitige Konfiguration aller Worker |
| **SubagentRouter** | wählt Worker + Runner pro Task |
| **LocalRunner** | heutiger In-Process-Subagent |
| **McpWorkerRunner** | Remote-Worker-Aufruf über MCP |
| **Capability** | Tag zur Routing-Entscheidung, z. B. `web`, `workspace`, `mcp:jira` |
| **Placement** | `local`, `remote` oder `any` |
| **Leaf Worker** | Worker ohne `subagent`/`task`-Tool (kein Fan-out) |

## 4. Ist-Zustand

- `subagent`-Tool: `agent`, `prompt`, `name`, `background`, `tasks[]`
  (`SubagentTool`).
- `tasks[]` wird **sequenziell** ausgeführt (`executeBatch`).
- `SubagentRunner` ist ein funktionales Interface; die einzige Implementierung
  ist `Engine.runSubagent` (in-process).
- Depth-Limit: max. 2 (`Engine.runSubagent`); globales Semaphore für
  Subagent-Concurrency.
- Hintergrundtasks: `BackgroundTasks` mit virtuellen Threads, in-process.
- MCP-Server exponiert `/mcp` mit `qoc_run_agent` und
  `qoc_run_agent_with_config`.
- Serverless-Modus lädt Agenten on-demand vom Server.
- A2A-Server nutzt einen **festen** Agenten (`engine.a2a.server.agent`);
  keine Agent-Auswahl pro Call.
- Eine Worker-Registry und ein Router existieren noch nicht.

## 5. Architektur

```
Orchestrator (Server, Workspace, Plan)
  │
  └─ subagent(agent, prompt, capability?, worker?, tasks[]?)
       │
       └─ SubagentRouter
            ├─ LocalRunner
            │    └─ Engine.runSubagent (in-process)
            │
            └─ WorkerPool (MCP /mcp → qoc_run_agent)
                 ├─ sls-web-1    (serverless, ephemer, web/MCP)
                 ├─ sls-web-2    (serverless, ephemer, web/MCP)
                 ├─ srv-shared-1 (server, shared Workspace, editor)
                 └─ srv-shared-2 (server, shared Workspace, reviewer)
```

Der Pool ist eine **logische** Ebene im Orchestrator:

- Serverless-Worker skalieren horizontal über die Plattform.
- Server-Worker sind registrierte, langlebige Endpunkte.
- Der Router kennt Fähigkeiten, Last, Health und Limits.

## 6. Transport

**Empfehlung: MCP (`POST /mcp` → `qoc_run_agent`) für Serverless- und
Server-Worker.**

Begründung:

- `qoc_run_agent` wählt Agent + Prompt + Modus pro Call.
- Liefert `_meta` mit Tokens, `sessionId`, `agentCreated`/`agentDeleted`.
- Beide Worker-Arten sprechen bereits `/mcp`.
- Einheitliche Auth (OIDC-Bearer) und Fehlerbehandlung.

A2A bleibt für externe Agent-Interoperabilität. Der QOC-A2A-Server nutzt einen
festen Agenten und ist daher für den internen Pool mit Agent-Auswahl pro Call
weniger geeignet.

## 7. Komponenten

### 7.1 WorkerRegistry

Serverseitige Konfiguration (settings.json oder Env). **Nicht** vom Modell
beeinflussbar.

```json
{
  "workers": {
    "sls-web-1": {
      "kind": "mcp",
      "url": "https://sls-web-1.example.com/mcp",
      "auth": "delegated",
      "agents": ["web-researcher"],
      "capabilities": ["web", "mcp:firecrawl"],
      "placement": "remote",
      "maxConcurrency": 4,
      "timeoutMs": 600000,
      "healthPath": "/api/health",
      "healthIntervalSeconds": 30,
      "retry": { "maxAttempts": 3, "backoffMs": [10000, 30000] },
      "circuitBreaker": { "failures": 3, "cooldownSeconds": 60 },
      "egress": { "allowedHosts": ["sls-web-1.example.com"], "tlsRequired": true }
    },
    "srv-shared-1": {
      "kind": "mcp",
      "url": "https://worker-1.example.com/mcp",
      "auth": "delegated",
      "agents": ["editor", "reviewer"],
      "capabilities": ["workspace"],
      "placement": "remote",
      "maxConcurrency": 2,
      "timeoutMs": 600000
    }
  }
}
```

- Secrets stehen **nicht** in der Registry, sondern im Credential-Store oder
  in Env-Variablen.
- `auth`: `delegated` (Caller-Token/Token-Exchange), `service`
  (Client-Credentials) oder `none` (nur lokal).
- `workers` muss in die `KNOWN`-Key-Liste von `EngineConfig` aufgenommen
  werden.

### 7.2 SubagentRouter

Erweitert `SubagentRunner` um einen Composite-Runner:

```java
final class SubagentRouter implements SubagentRunner {
    SubagentResult run(SessionId parent, String agent, String prompt, int depth);
}
```

Aufgaben:

- Auswahl des Runners (local oder remote)
- Lastverteilung
- Health/Failover
- Timeout/Retry
- Metadaten (Worker-ID, Latenz, Tokens)

### 7.3 LocalRunner

Bestehender `Engine.runSubagent`:

- Agent muss lokal existieren und `isSubagent` erfüllen.
- Depth-Limit und Semaphore bleiben.
- Workspace-gebunden.

### 7.4 McpWorkerRunner

- Baut einen JSON-RPC-Call:
  ```json
  {"jsonrpc":"2.0","id":1,"method":"tools/call",
   "params":{"name":"qoc_run_agent",
             "arguments":{"agent":"web-researcher","prompt":"...",
                          "mode":"ephemeral","sessionMode":"new","retain":false}}}
  ```
- Optional `qoc_run_agent_with_config` mit `agentConfig` (dynamische
  Agent-Config).
- Header:
  - `Authorization: Bearer <delegated token>`
  - `X-QOC-Depth`, `X-QOC-Trace-Id`, `X-QOC-Parent-Session`
  - `Idempotency-Key`
- Antwort mappen:
  - `content[].text` → `SubagentResult.text`
  - `_meta.inputTokens/outputTokens` → `TokenCount`
  - `_meta.sessionId` → `childSession`
  - `_meta.warnings` → Metadaten

### 7.5 Health, Retry, Circuit Breaker

- **Health:** periodischer Check (`/api/health` oder MCP `initialize`/
  `tools/list`); Worker mit Fehler wird temporär aus dem Pool genommen.
- **Retry:** max. 3 Versuche, Backoff 10s/30s; nur bei Transportfehler,
  Timeout oder 5xx.
- **Circuit Breaker:** nach N Fehlern Cooldown; danach Halb-Open-Test.
- **Failover:** anderer Worker mit gleicher Capability.

## 8. Worker-Deskriptor

| Feld | Typ | Bedeutung |
|---|---|---|
| `kind` | `mcp` (MVP), `a2a` (später) | Transport |
| `url` | string | MCP-Endpunkt |
| `auth` | `delegated`/`service`/`none` | Identität |
| `agents[]` | string[] | unterstützte Agenten |
| `capabilities[]` | string[] | Routing-Tags |
| `placement` | `local`/`remote`/`any` | bevorzugter Ort |
| `maxConcurrency` | int | parallele Calls pro Worker |
| `timeoutMs` | int | Call-Timeout |
| `healthPath` | string | Health-Endpunkt |
| `retry` | object | Versuche/Backoff |
| `circuitBreaker` | object | Fehler/Cooldown |
| `egress` | object | Host-/TLS-Allow-List |

## 9. `subagent`-Tool-Interface

Bestehende Felder bleiben kompatibel. Neue optionale Felder:

```json
{
  "agent": "web-researcher",
  "prompt": "Analysiere URL A",
  "worker": "sls-web-1",
  "capability": "web",
  "timeoutMs": 600000,
  "agentConfig": "---\nname: inline\nmode: all\n---\n...",
  "background": false,
  "tasks": [
    {"name": "source-1", "agent": "web-researcher", "prompt": "URL A"},
    {"name": "source-2", "agent": "web-researcher", "prompt": "URL B"}
  ]
}
```

- `worker`: explizite Worker-ID (Vorrang)
- `capability`: Pool-Auswahl nach Fähigkeit
- `agentConfig`: Worker führt `qoc_run_agent_with_config` aus
- `tasks[]`: Batch, parallel ausgeführt
- `background`: wie heute, aber remote-fähig

## 10. Routing

Priorität:

1. Explizites `worker`
2. `capability` + Agent-Name
3. Agent-Name → Worker-Mapping (`agents[]`)
4. `placement`:
   - `workspace` → local oder Server-Worker mit Shared Workspace
   - `web`/`mcp` → Serverless-Pool
5. Lastverteilung:
   - least-in-flight
   - round-robin
   - random
6. Health/Circuit-Breaker: kranke Worker überspringen
7. Fallback: local, wenn Agent lokal existiert und `placement=any`

Der Router akzeptiert **nur registrierte Worker-IDs**. Das Modell kann keine
URLs oder Hosts bestimmen.

## 11. Parallelität

- `tasks[]` wird parallel ausgeführt.
- Begrenzung:
  - globales Pool-Limit
  - `maxConcurrency` pro Worker
  - bestehendes Subagent-Semaphor für lokale Runner
- `background=true`: virtueller Thread wartet auf den Remote-Call; `task`-Tool
  pollt den Status.
- Partielle Ergebnisse: Batch liefert pro Task `ok|failed`.
- Timeouts pro Task; keine unendlichen Läufe.

## 12. Fehlerbehandlung, Idempotenz, Depth

- Retry nur bei Transport/5xx/Timeout, nicht bei fachlichen Agent-Fehlern.
- Idempotency-Key pro Task an den Worker; Worker dedupliziert.
- Depth:
  - `X-QOC-Depth` beginnt bei 0 im Orchestrator.
  - Remote-Worker lehnt Depth ≥ `maxDepth` ab.
  - Leaf-Worker haben `subagent`/`task` deny.
- Loop-Schutz zusätzlich über `X-QOC-Trace-Id` und `X-QOC-Parent-Session`.
- Fehler werden als `ToolOutcome.Failed` mit `workerId`, `taskName` und
  `retryable` zurückgegeben.

## 13. Identität, Sicherheit, Audit

- **User-getriggerte Calls:** Caller-Token weiterreichen oder per RFC 8693
  Token Exchange auf die Worker-Audience tauschen (`act`-Claim).
- **Systemtasks:** Service-Identity mit engem Scope.
- **Scopes:** `worker:run`, `agent:read`, `mcp:run`; Worker haben kein
  `agent:write`.
- **Egress:** Allow-List für Worker-Hosts, TLS-Pflicht optional.
- **Audit:** pro Task `workerId`, `agent`, `principal`, `act`, Tokens, Latenz,
  Ergebnisstatus.
- **Keine Secrets in der Registry.**

## 14. Ergebnis-Contract

`SubagentResult` erweitern:

```java
record SubagentResult(
    String text,
    TokenCount usage,
    SessionId childSession,
    String workerId,
    String workerKind,      // local | mcp | a2a
    long latencyMs,
    Map<String, Object> meta
)
```

Tool-Ergebnis pro Task:

```json
{
  "name": "source-1",
  "workerId": "sls-web-1",
  "workerKind": "mcp",
  "latencyMs": 4210,
  "tokens": { "input": 1200, "output": 300 },
  "text": "...",
  "warnings": []
}
```

## 15. Konfiguration

### settings.json

```json
{
  "workers": {
    "sls-web-1": { "kind": "mcp", "url": "http://host.docker.internal:8072/mcp",
                   "auth": "delegated", "agents": ["web-researcher"],
                   "capabilities": ["web"], "placement": "remote",
                   "maxConcurrency": 4, "timeoutMs": 600000 },
    "srv-shared-1": { "kind": "mcp", "url": "http://worker-1:8080/mcp",
                      "auth": "delegated", "agents": ["editor"],
                      "capabilities": ["workspace"], "placement": "remote",
                      "maxConcurrency": 2, "timeoutMs": 600000 }
  }
}
```

### Umgebungsvariablen (Overrides)

```env
ENGINE_WORKERS_CONFIG=/data/home/.config/opencode/workers.json
ENGINE_WORKER_MAX_CONCURRENCY=8
ENGINE_WORKER_RETRY_MAX_ATTEMPTS=3
ENGINE_WORKER_RETRY_BACKOFF_MS=10000,30000
ENGINE_WORKER_HEALTH_INTERVAL_SECONDS=30
ENGINE_WORKER_CIRCUIT_FAILURES=3
ENGINE_WORKER_CIRCUIT_COOLDOWN_SECONDS=60
```

## 16. Deployment-Topologien

### Lokal (Docker + Keycloak)

- Orchestrator: `qoc-server` auf 8073.
- Serverless-Worker: `qoc-minimal` auf 8072.
- Registry-Eintrag:
  `http://host.docker.internal:8072/mcp`.
- Parallelität lokal: `ENGINE_MAX_CONCURRENT_PROMPTS=4` im Serverless.

### VM + Serverless (GCP/AWS)

- Orchestrator/Control Plane auf VM.
- Serverless-Worker: Lambda Function URL / Cloud Run URL.
- Server-Worker: weitere VMs mit Shared Workspace.
- Registry-Einträge mit HTTPS, OIDC-Audience, Egress-Allow-List.

### Nur Server-Worker

- Mehrere QOC-Instanzen mit gemeinsamem Workspace-Mount (read-only
  Agent-Config).
- Registry-Einträge per interner URL.
- Exklusives Agent-Authoring beim Editor/Orchestrator.

## 17. Observability

- Metriken pro Worker:
  - in-flight
  - Latenz (p50/p95)
  - Fehlerrate
  - Retries
  - Tokens/Kosten
- Tracing: `traceparent` an den Worker weiterreichen; Span pro Call.
- Audit-Log für jeden Worker-Aufruf.
- Health-Status im `/api/worker`-Endpoint (neu) oder in `/api/config`.

## 18. Implementierungsplan

> Die cloud-spezifische Ausrollplanung (GCP-Pool + AWS-Dedicated-Tool) steht
> in Abschnitt 22.

### Phase 1 – Registry + explizites Routing

1. `workers`-Block in `EngineConfig` parsen (inkl. `KNOWN`-Keys).
2. `WorkerRegistry` mit Deskriptoren und Validierung.
3. `McpWorkerRunner` (JSON-RPC `qoc_run_agent`).
4. `SubagentRouter` mit `worker`-Feld.
5. `subagent`-Schema um `worker`/`capability`/`timeoutMs` erweitern.

### Phase 2 – Pool-Verhalten

1. Capability- und Agent-Mapping-Routing.
2. Least-in-flight + Round-Robin.
3. Health-Checks, Retry, Circuit Breaker, Failover.
4. Batch parallelisieren (bounded Executor).
5. Background-Tasks remote-fähig.

### Phase 3 – Komfort und Härtung

1. `agentConfig` (dynamische Agent-Config) im Subagent-Tool.
2. Token Exchange (RFC 8693) statt Raw-Forwarding.
3. Metriken, Audit, `/api/worker`-Status.
4. Optional: dynamische Discovery (MCP `tools/list`, A2A Agent Cards).
5. Optional: A2A-Transport für externe Worker.

## 19. Testplan

- Registry-Parsing und Validierung.
- Routing: explizit, Capability, Agent-Mapping, Fallback.
- Lastverteilung: least-in-flight, round-robin.
- Retry: 3 Versuche, Backoff, keine Retry bei 4xx.
- Circuit Breaker: Ausfall und Recovery.
- Failover auf zweiten Worker.
- Paralleler Batch: 3 Tasks, 2 Worker, korrekte Zuordnung.
- Depth-Limit und Loop-Schutz.
- Auth: Caller-Token/Exchange, 401/403-Fälle.
- Egress-Allow-List (kein SSRF).
- Ergebnis-Mapping (`_meta`, Tokens, Latenz).
- Integration lokal: Server + Serverless via `host.docker.internal`.
- JaCoCo ≥ 80 %.

## 20. Offene Entscheidungen

1. Transport: MCP für alle (empfohlen) oder Server-Worker über A2A?
2. Registry: statisch (MVP) oder dynamisch (Health/Agent-Card-Discovery)?
3. Auth: Caller-Token forwarden oder immer Token Exchange?
4. Dynamische Agent-Configs: `agentConfig` erlauben (empfohlen) oder nur
   benannte Agenten?
5. Placement: Agent-Config, Router-Policy oder Caller entscheidet?
6. Async: synchrone MCP-Calls im Hintergrund-Thread (MVP) oder echte
   Remote-Job-API?
7. Pool-Grenzen: globales Limit und pro-Worker-Limit — wo konfiguriert?
8. Sollen Server-Worker eigene `ENGINE_ROLE=worker`-Capabilities melden?

## 21. Cloud-Topologien und Tool-Oberfläche (GCP vs. AWS)

### 21.1 Entscheidung

Die Tool-Oberfläche folgt der Vertrauens- und Cloud-Grenze, nicht der Anzahl
der Agenten:

| Umgebung | Tool-Oberfläche | Begründung |
|---|---|---|
| Local + GCP Cloud Run | **generisches `subagent`** mit `environment=gcp` | gleiche GCP-/OIDC-/Egress-Domäne wie die Orchestrator-VM; config-getrieben, skalierbar |
| AWS Lambda | **dediziertes `aws_worker`-Tool** | andere Cloud, andere Auth, andere Limits, andere Policy |
| Shared-Workspace-Worker (optional) | eigenes `delegate_workspace` | Schreib-/Approval-Policy als eigene Grenze |
| Externe A2A-Agenten (optional) | `delegate_external` | andere Vertrauensgrenze/Transport |

**Nicht** einführen: ein Tool pro Agent, pro Cloud-Run-Instanz oder pro
MCP-Server.

### 21.2 GCP: generischer Worker-Pool

Der Orchestrator läuft auf der GCP-VM, die Worker auf Cloud Run. Beide teilen:

- denselben OIDC-Tenant (Auth0/Keycloak),
- dieselbe Egress-/Netzwerk-Domäne,
- dieselbe Observability (GCP Logging/Monitoring),
- dieselbe Secret-Verwaltung.

Deshalb reicht ein generisches `subagent`-Tool:

```json
{
  "agent": "web-researcher",
  "environment": "gcp",
  "capability": "web",
  "prompt": "Analysiere URL A",
  "tasks": [
    {"name": "source-1", "agent": "web-researcher", "prompt": "URL A"},
    {"name": "source-2", "agent": "web-researcher", "prompt": "URL B"}
  ]
}
```

Registry:

```json
{
  "workers": {
    "gcp-web-1": {
      "kind": "mcp",
      "url": "https://qoc-worker-1-xyz.a.run.app/mcp",
      "auth": "delegated",
      "environment": "gcp",
      "agents": ["web-researcher"],
      "capabilities": ["web", "mcp:firecrawl"],
      "placement": "remote",
      "maxConcurrency": 4,
      "timeoutMs": 600000,
      "egress": { "allowedHosts": ["qoc-worker-1-xyz.a.run.app"], "tlsRequired": true }
    },
    "gcp-web-2": {
      "kind": "mcp",
      "url": "https://qoc-worker-2-xyz.a.run.app/mcp",
      "auth": "delegated",
      "environment": "gcp",
      "agents": ["web-researcher"],
      "capabilities": ["web", "mcp:firecrawl"],
      "placement": "remote",
      "maxConcurrency": 4,
      "timeoutMs": 600000
    }
  }
}
```

Vorteile:

- Neue Cloud-Run-Worker sind reine Config-Einträge.
- Der Router übernimmt Lastverteilung, Health, Retry, Failover.
- Kein zusätzliches Tool im Prompt; der Agent-Katalog führt das Modell.
- Einheitliche Metriken/Audit über alle GCP-Worker.

### 21.3 AWS: dediziertes Tool

Der AWS-Lambda-Worker ist eine eigene Vertrauensgrenze. Ein dediziertes Tool
kapselt:

- festen Ziel-Worker (kein Modell-gesteuertes Routing),
- Cross-Cloud-Auth,
- Lambda-spezifische Limits,
- eigene Policy (kein Workspace, keine Shell, nur Web/MCP),
- CloudWatch-Trace-Korrelation.

Tool-Schema:

```json
{
  "name": "aws_worker",
  "description": "Run a stateless task on the AWS Lambda worker (cross-cloud, no shared workspace).",
  "input": {
    "prompt": "string",
    "agent": "string (optional, enum aus Allow-List)",
    "structuredOutput": "object (optional JSON Schema)",
    "timeoutMs": "integer (optional, max 900000)"
  }
}
```

Intern:

- fester Worker `aws-lambda-1`
- `McpWorkerRunner` → `qoc_run_agent` oder `qoc_run_agent_with_config`
- `mode=ephemeral`, `sessionMode=new`, `retain=false`
- Auth: Token Exchange auf `aud=qoc-aws-worker` oder Service-Identity
- Ergebnis: Text + `structuredContent` + `_meta` (Tokens, Latenz,
  `X-Amzn-Trace-Id`)

Registry:

```json
{
  "workers": {
    "aws-lambda-1": {
      "kind": "mcp",
      "url": "https://abc123.lambda-url.eu-central-1.on.aws/mcp",
      "auth": "exchange",
      "environment": "aws",
      "agents": ["aws-worker"],
      "capabilities": ["batch", "web"],
      "placement": "remote",
      "maxConcurrency": 8,
      "timeoutMs": 900000,
      "egress": { "allowedHosts": ["abc123.lambda-url.eu-central-1.on.aws"], "tlsRequired": true }
    }
  },
  "tools": {
    "aws_worker": {
      "worker": "aws-lambda-1",
      "description": "Run a stateless task on the AWS Lambda worker."
    }
  }
}
```

### 21.4 Tool-Landschaft

| Tool | Ziel | Zweck |
|---|---|---|
| `subagent` | local + GCP-Cloud-Run-Pool | generische Delegation, Batch, dynamische Agenten |
| `aws_worker` | AWS-Lambda-Worker | dedizierte Cross-Cloud-Aufgaben |
| `delegate_workspace` (optional) | Shared-Workspace-Worker | Schreib-/Approval-Policy getrennt |
| `delegate_external` (optional) | A2A/externe Agenten | Interoperabilität |

Alle Tools nutzen intern denselben `SubagentRouter`/`McpWorkerRunner`. Die
dedizierten Tools sind dünne Fassaden mit festem Ziel und engerem Schema.

### 21.5 Cross-Cloud-Auth, Transport und Limits

| Thema | Empfehlung |
|---|---|
| Auth | Bevorzugt gleicher Auth0-Tenant + RFC 8693 Token Exchange auf `aud=qoc-aws-worker`. Alternativ Service-Token. |
| Transport | HTTP Function URL mit MCP (`/mcp`); bei IAM-Auth ein SigV4-Adapter vor dem MCP-Call. |
| Timeout | Lambda max. 15 min; QOC-Worker `turn.max.wall-minutes=10`; Tool-Timeout entsprechend. |
| Payload | Lambda sync max. 6 MB; keine Workspace-Dateien übertragen, nur Text/JSON. |
| Cold Start | Optional Provisioned Concurrency; Tool kann `background=true` nutzen. |
| Egress | GCP-VM → AWS Function URL über öffentliches Internet oder PrivateLink/VPC-Endpoint; Allow-List. |
| Observability | `X-Amzn-Trace-Id` und CloudWatch-Log-Referenz in `_meta` zurückgeben. |
| Secrets | AWS Secrets Manager / GCP Secret Manager, nicht in der Registry. |
| Funktion-URL-Auth | `auth-type NONE` nur mit OIDC im QOC-Prozess; besser IAM-Auth oder WAF davor. |

### 21.6 Wann ein GCP-Worker ebenfalls ein dediziertes Tool bekommt

Nicht für jeden Agenten. Nur wenn mindestens zwei Punkte zutreffen:

1. anderer Input-/Output-Contract (z. B. Patch-Review, strukturiertes JSON),
2. andere Security-Policy (Workspace-Writes, Approval, Egress),
3. andere Umgebung/Topologie (Shared Workspace statt ephemer),
4. eigener Audit-/Compliance-Pfad,
5. häufige Nutzung mit hohem Fehlbedienungsrisiko.

Sonst bleibt er ein Agent hinter dem generischen `subagent`-Tool.

### 21.7 Sicherheit und Observability

- Der Tool-Name und die Beschreibung sagen dem Modell klar, welche Umgebung
  gemeint ist (`aws_worker` = AWS, stateless, kein Workspace).
- Das Modell kann keine Worker-URLs setzen; nur registrierte IDs/Aliase.
- Audit pro Call: `workerId`, `environment`, `agent`, `principal`, `act`,
  Tokens, Latenz, Cloud-Trace-ID.
- Metriken getrennt nach `environment` (`gcp`, `aws`, `local`), damit
  Cross-Cloud-Latenz und Fehlerraten sichtbar sind.

### 21.8 MCP-Proxy-Variante: mehrere Tool-Profile pro Worker

Ein MCP-Server kann mehrere Tools in `tools/list` anbieten. QOC registriert
jedes Tool namespaced als `<server>_<tool>` (`McpTool`). Der Agent steuert den
Zugriff über `mcpServers`, `tools.allow/deny` und `permissions`.

#### Drei Ausprägungen

| Variante | Aufbau | Tool-Namen in QOC | Zusatzdienst |
|---|---|---|---|
| **A: Direkt** | QOC → Lambda-`/mcp` | `aws_qoc_run_agent`, `aws_qoc_run_agent_with_config` | nein |
| **B: MCP-Proxy** | QOC → MCP-Proxy → AWS | `aws_worker`, `aws_task_status`, … | ja |
| **C: Proxy + QOC-Wrapper** | QOC-ToolDefinition → MCP-Proxy | stabiles `aws_worker` | ja |

- **A** ist der schnellste Weg: Der QOC-Lambda-Worker exponiert bereits `/mcp`.
  Der Agent sieht die generischen Worker-Tools.
- **B** kapselt AWS-Auth, Lambda-Invoke, Limits und Trace-IDs in einem eigenen
  MCP-Server. Der Proxy definiert die Tool-Schemas.
- **C** wrappt den Proxy-Tool in ein QOC-`ToolDefinition` mit stabilem Namen,
  engem Schema und eigener Policy. Der Proxy bleibt austauschbar.

#### Mehrere Tool-Profile auf einem Server

Ein Worker bzw. Proxy kann gleichzeitig generische und dedizierte Tools
anbieten. Empfohlene Namen (Server `aws`):

| Tool auf dem Server | Effektiver Name | Schema | Zweck |
|---|---|---|---|
| `worker` | `aws_worker` | `prompt`, `structuredOutput`, `timeoutMs` | dediziert, fester Agent, feste Policy |
| `agent` | `aws_agent` | `agent`, `prompt`, `structuredOutput`, `timeoutMs` | generisch, Agent wählbar |
| `inline` | `aws_inline` | `agentConfig`, `prompt`, `maxSteps`, `timeoutMs` | dynamische Agent-Config |

Namens-Falle: QOC präfixt mit dem **Server-Namen**. Server `aws` + Tool `worker`
ergibt `aws_worker`. Server `aws` + Tool `aws_worker` ergibt `aws_aws_worker`.

Beispiel `tools/list`:

```json
[
  {
    "name": "worker",
    "description": "Run a task on the dedicated AWS worker (fixed agent, no agent selection).",
    "inputSchema": {
      "type": "object",
      "properties": {
        "prompt": { "type": "string" },
        "structuredOutput": { "type": "object" },
        "timeoutMs": { "type": "integer" }
      },
      "required": ["prompt"]
    }
  },
  {
    "name": "agent",
    "description": "Run an allowed QOC agent on the AWS worker.",
    "inputSchema": {
      "type": "object",
      "properties": {
        "agent": { "type": "string" },
        "prompt": { "type": "string" },
        "structuredOutput": { "type": "object" },
        "timeoutMs": { "type": "integer" }
      },
      "required": ["agent", "prompt"]
    }
  }
]
```

#### Zugriff über die Agent-Config

Drei Ebenen greifen:

1. **`mcpServers`** – Namespace-Freigabe; ohne `"aws"` werden alle AWS-Tools
   entfernt.
2. **`tools.allow` / `tools.deny`** – konkrete Tool-Namen.
3. **`permissions`** – feinere Regeln pro Aktion/Ressource.

```json
{
  "name": "aws-specialist",
  "mcpServers": ["aws"],
  "tools": {
    "allow": ["aws_worker"],
    "deny": []
  },
  "permissions": [
    { "action": "aws_worker", "resource": "*", "effect": "allow" }
  ]
}
```

Zugriffsmatrix:

| Agent | `mcpServers` | `tools.allow` | Sichtbare AWS-Tools |
|---|---|---|---|
| `aws-specialist` | `["aws"]` | `["aws_worker"]` | nur dediziertes Worker-Tool |
| `aws-generalist` | `["aws"]` | `["aws_agent"]` | generisches Agent-Tool |
| `aws-power` | `["aws"]` | `["aws_worker", "aws_agent"]` | beide |
| `local-only` | `[]` | `["*"]` | keine AWS-Tools |

Der Namespace-Filter greift auch bei `tools.allow: ["*"]`: Ohne
`mcpServers: ["aws"]` bleiben die Tools unsichtbar.

#### MCP-Konfiguration in QOC

```json
{
  "mcp": {
    "aws": {
      "type": "remote",
      "url": "https://mcp-aws.example.com/mcp",
      "headers": { "Authorization": "Bearer ${AWS_MCP_TOKEN}" }
    }
  }
}
```

- `${AWS_MCP_TOKEN}` kommt aus Env/Secret Manager, nicht aus `settings.json`.
- `tools/list` wird beim Start gelesen; ist der Server nicht erreichbar, fehlen
  die Tools.
- Für Remote-MCP-Server fehlt aktuell eine Egress-Allow-List wie bei A2A
  (`allowedHosts`/`tlsRequired`); für AWS-URLs ergänzen.

#### Per-User-Identität (wichtige Einschränkung)

QOCs MCP-Client sendet aktuell statische `headers` und optional einen aktiven
Credential-Store-Token. Er forwards **nicht** automatisch den Bearer-Token des
aufrufenden Users. Der AWS-Proxy sieht daher eine Service-Identity.

Optionen:

1. Token Exchange (RFC 8693) vor dem MCP-Call, `aud=aws-mcp`,
   `scope=worker:run`, `act`-Claim.
2. Zusätzliche Header `X-QOC-User`/`X-QOC-Act` (nur mit Vertrauensstellung).
3. Vorerst Service-Identity akzeptieren und den QOC-Kontext auditieren.

#### Wann welche Variante?

- **A** für MVP und schnelle Inbetriebnahme ohne Zusatzdienst.
- **B** wenn AWS-Auth/Limits/Trace-ID unabhängig von QOC entwickelt werden
  sollen.
- **C** wenn der Orchestrator ein stabiles, schmales `aws_worker`-Tool mit
  eigener Policy/Audit braucht.

#### Implementierungshinweis

- **A:** Der Lambda-Worker kann `McpServerResource.toolsList()`/`toolsCall()`
  um Tool-Profile erweitern (z. B. `worker`, `agent`, `inline`), gesteuert über
  `ENGINE_MCP_TOOL_PROFILES`.
- **B/C:** Der externe MCP-Proxy definiert die Tools; QOC-Seite ist reine
  Config (B) bzw. ein dünner Wrapper (C).
- Der Agent-Config-Zugriff bleibt in allen Varianten gleich.

---

## 22. Umsetzungsplan GCP/AWS

### Phase 1 – Gemeinsame Infrastruktur

1. `workers`-Block in `EngineConfig` parsen (inkl. `KNOWN`-Keys).
2. `WorkerRegistry` mit Deskriptoren (`environment`, `auth`, `capabilities`,
   Limits, Egress).
3. `McpWorkerRunner` (JSON-RPC `qoc_run_agent`).
4. `SubagentRouter` mit `worker`, `environment` und `capability`.
5. `subagent`-Schema erweitern (`environment`, `worker`, `capability`,
   `timeoutMs`, `agentConfig`).
6. Batch parallelisieren (bounded Executor).

### Phase 2 – GCP-Pool

1. Cloud-Run-Worker als Registry-Einträge (`environment=gcp`).
2. Routing: `environment=gcp` + `capability` + least-in-flight.
3. Health-Checks gegen Cloud Run (`/api/health`).
4. Delegated Token (Caller-Token oder Exchange) an Cloud Run.
5. Egress-Allow-List für Cloud-Run-Hosts.
6. Lokaler Integrationstest: Orchestrator auf 8073, Worker auf 8072.

### Phase 3 – AWS-Dedicated-Tool

**Variante A – MCP-Tool-Profile im Lambda-Worker:**

1. `McpServerResource.toolsList()`/`toolsCall()` um Tool-Profile erweitern
   (`worker`, `agent`, `inline`), gesteuert über `ENGINE_MCP_TOOL_PROFILES`.
2. `worker`: festes Schema (`prompt`, `structuredOutput`, `timeoutMs`), fester
   Agent, feste Policy.
3. `agent`: generisch (`agent`, `prompt`, `structuredOutput`, `timeoutMs`).
4. `inline`: `agentConfig` + `prompt`, nur für explizit freigegebene Agenten.
5. QOC-Seite: Remote-MCP-Server `aws` konfigurieren; Agent-Config steuert
   Zugriff über `mcpServers`, `tools.allow/deny`, `permissions`.
6. Tool-Namen: Server `aws` + Tool `worker` → `aws_worker` (kein Doppelpräfix).

**Variante C – QOC-Wrapper (optional):**

1. `aws_worker`-Tool aus der Registry generieren (fester Worker-Alias).
2. Enges Schema: `prompt`, optional `structuredOutput`, `timeoutMs`.
3. Auth-Adapter: Token Exchange auf `aud=qoc-aws-worker` oder Service-Token.

**Gemeinsam:**

1. Lambda-Limits: Timeout ≤ 900s, Payload ≤ 6 MB, keine Workspace-Dateien.
2. `_meta` um `environment=aws`, `workerId`, `latencyMs`, `X-Amzn-Trace-Id`
   erweitern.
3. Fehler-Mapping: Cold-Start-Timeout, 413 Payload Too Large, 401/403,
   Lambda-5xx.
4. Optional: `background=true` für lange Lambda-Läufe.

### Phase 4 – Härtung

1. Retry (3 Versuche, Backoff 10s/30s) und Circuit Breaker pro Worker.
2. Failover innerhalb des GCP-Pools; AWS-Tool mit klarer Fehlermeldung.
3. Metriken/Audit pro `environment`.
4. `/api/worker`-Status-Endpoint (Registry, Health, in-flight).
5. Optional: Provisioned Concurrency für AWS; WAF/IAM vor der Function URL.
6. Optional: dynamische Discovery (Cloud Run Service Discovery, AWS
   Function-URL-Registry).

### Tests

- GCP: 3 parallele `tasks[]` auf 2 Cloud-Run-Workern, korrekte Zuordnung.
- GCP: Failover bei einem kranken Worker.
- AWS: `aws_worker` erreicht nur den konfigurierten Lambda-Worker.
- AWS: Tool-Profile `aws_worker`/`aws_agent`/`aws_inline` werden korrekt
  namespaced und nur gemäß Agent-Config sichtbar.
- AWS: `mcpServers`-Filter greift auch bei `tools.allow: ["*"]`.
- AWS: Auth-Fehler, Timeout, 413, Cold-Start-Retry.
- Cross-Cloud: Token Exchange/Audience-Prüfung.
- Security: keine freie URL, Egress-Allow-List greift.
- Ergebnis: `_meta` enthält `environment`, `workerId`, Latenz, Cloud-Trace.
- JaCoCo ≥ 80 %.

### Acceptance Criteria

- `subagent(environment=gcp, capability=web, tasks=[...])` läuft parallel auf
  Cloud Run und liefert pro Task ein Ergebnis.
- `aws_worker(prompt=...)` läuft auf dem AWS-Lambda-Worker, liefert Text/
  strukturiertes Ergebnis und eine AWS-Trace-Referenz.
- Ein neuer GCP-Worker ist ohne Code-Deploy nur per Registry-Eintrag nutzbar.
- Ein Worker-Ausfall führt zu Retry/Failover bzw. einer klaren Fehlermeldung.
- AWS-Worker hat keinen Workspace-Zugriff und keine Shell.

### Risiken und Gegenmaßnahmen

| Risiko | Gegenmaßnahme |
|---|---|
| Cross-Cloud-Latenz | Timeouts, `background=true`, Provisioned Concurrency, PrivateLink |
| Lambda Cold Start | Provisioned Concurrency oder warme Mindestinstanz |
| Zwei IdPs/Audiences | Token Exchange zentral im Auth-Adapter, nicht im Tool |
| Datenresidenz | AWS-Worker nur stateless Text/JSON, keine Workspace-Daten |
| Function-URL öffentlich | IAM-Auth/WAF, OIDC im QOC-Prozess, Rate-Limit |
| Payload-Limit | Strukturiertes Output statt Dateien, 6-MB-Check vor dem Call |

### Offene Entscheidungen (Cloud)

1. AWS-Auth: Token Exchange (empfohlen) oder Service-Token?
2. Function-URL: `auth-type NONE` + OIDC oder IAM/SigV4?
3. AWS-Tool: fest ein Ziel oder mehrere AWS-Worker mit Alias?
4. GCP-Worker: Cloud Run direkt oder über API Gateway?
5. PrivateLink/VPC-Endpoint nötig oder öffentliches Internet mit Allow-List?
6. Sollen AWS-Aufgaben standardmäßig `background=true` sein?
7. AWS-Tool-Profile: nur `worker` (dediziert), `agent` (generisch) oder beide?
8. Soll `inline` (`agentConfig`) überhaupt freigegeben werden – und für welche
   Agenten?
9. MCP-Proxy (Variante B) oder Tool-Profile direkt im Lambda-Worker
   (Variante A)?

## 23. Referenzen

- `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentTool.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentRunner.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java` (`runSubagent`)
- `quarkus/src/main/java/de/augmentia/oc/engine/http/McpServerResource.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/mcp/McpDiscoveryClient.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/A2AConfig.java`
- `docs/guides/deployment-modes.md`

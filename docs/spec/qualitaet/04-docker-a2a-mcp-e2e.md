# 04 – Docker-E2E: A2A und MCP über drei Container

> **Subject:** End-to-End-Testkonzept für die Interoperabilität von QOC über zwei
> Protokolle (A2A, MCP) in drei Containern mit unterschiedlichen Agents.
>
> **Basis:** `docs/analysis/a2a.md` (Phase 1–3), `docs/spec/tools/04-mcp.md`,
> `docs/spec/tools/09-mcp-server.md`, `docs/Security.md` §4.8a, `deploy/Dockerfile`.
>
> **Status:** 2026-10-28 · Spec-Dokument (keine Implementierung).

## 1. Ziel

Verifizieren, dass ein QOC-Agent (Container A) mit zwei unterschiedlichen
Protokollen gleichzeitig arbeitet:

- **A2A** zu einem zweiten QOC-Agent (Container B) – Agent-to-Agent,
- **MCP** zu einem dritten Container (Container C) – QOC als MCP-Client zu
  einem MCP-Server.

Und umgekehrt, dass Container A selbst als **A2A-Server** und als
**MCP-Server** ansprechbar ist. Die drei Container laufen isoliert, ohne
externe LLMs (scripted Provider), und tauschen echte JSON-RPC-Nachrichten
aus.

**Nicht im Scope:** OIDC (eigener Testplan `docs/plans/e2e-security-test.md`),
Performance/Last, echte LLM-Antworten.

## 2. Container-Topologie

```
┌─────────────────────────────────────────────────────────────────────┐
│  Docker-Netzwerk: qoc-interop (bridge)                              │
│                                                                     │
│  ┌──────────────┐   A2A    ┌──────────────┐   MCP    ┌────────────┐ │
│  │  qoc-agent-a │ ◄──────► │  qoc-agent-b │          │ mcp-server │ │
│  │  (Orchestr.)  │          │  (Peer)      │          │ (Container │ │
│  │  A2A srv+cli  │ ─────────────────────────────────► │  C)        │ │
│  │  MCP cli+srv  │          │              │          │            │ │
│  └──────┬───────┘          └──────────────┘          └────────────┘ │
│         │                                                            │
│         └── published: 127.0.0.1:8080 (nur A)                      │
└─────────────────────────────────────────────────────────────────────┘
```

| Container | Rolle | Protokolle | Agent-Name |
|---|---|---|---|
| `qoc-agent-a` | Orchestrator (SUT) | A2A-Server, A2A-Client, MCP-Client, MCP-Server | `Agent A` |
| `qoc-agent-b` | Peer-Agent | A2A-Server (nur) | `Agent B` |
| `mcp-server` | MCP-Server | MCP-Server (`qoc_run_agent` oder eigenes Tool-Set) | – |

**Regel:** Container A ist der einzige, der beide Protokolle **als Client**
nutzt. Container B und C sind reine Server. Damit ist der Datenfluss
eindeutig und debuggbar.

## 3. Agenten und ihre Aufgaben

| Agent | Container | Beschreibung | Erwartete Antwort (scripted) |
|---|---|---|---|
| `agent-a` | A | primärer Agent, nutzt A2A-Tool `a2a_agent_b` und MCP-Tool `mcp_echo` | „Hallo aus der Session Engine." |
| `agent-b` | B | Peer-Agent, antwortet auf A2A-`SendMessage` | „Hallo aus der Session Engine." |
| `mcp-echo` | C | MCP-Server mit Tool `echo(text)` (oder `qoc_run_agent` auf B) | Echo des Eingabetexts |

Der scripted Provider (`ENGINE_PROVIDER_SCRIPTED=true`) liefert deterministische
Antworten, sodass keine LLM-API nötig ist.

## 4. Konfiguration

### 4.1 Gemeinsame Basis (alle QOC-Container)

```env
ENGINE_PROVIDER_SCRIPTED=true
ENGINE_DB_KIND=sqlite
ENGINE_DB_PATH=/data/qoc.db
ENGINE_WORKSPACE=/data/workspace
ENGINE_SECURITY_MODE=basic
OPENCODE_SERVER_PASSWORD=interop-secret
QUARKUS_OTEL_ENABLED=false
```

### 4.2 Container A – `qoc-agent-a`

```env
ENGINE_A2A_SERVER_ENABLED=true
ENGINE_A2A_SERVER_NAME=Agent A
ENGINE_A2A_SERVER_BASE_URL=http://qoc-agent-a:8080
ENGINE_A2A_SERVER_AGENT=none

ENGINE_A2A_ENDPOINTS=id=agent-b|url=http://qoc-agent-b:8080|tool=a2a_agent_b|enabled=true
ENGINE_A2A_CACHE_TTL_SECONDS=60

ENGINE_MCP_SERVER_ENABLED=true
ENGINE_MCP_SERVER_OAUTH_ENABLED=false
```

`settings.json` (im Workspace oder global) für den MCP-Client:

```json
{
  "mcp": {
    "echo": {
      "type": "remote",
      "url": "http://mcp-server:8090/mcp",
      "disabled": false,
      "codemode": true
    }
  }
}
```

### 4.3 Container B – `qoc-agent-b`

```env
ENGINE_A2A_SERVER_ENABLED=true
ENGINE_A2A_SERVER_NAME=Agent B
ENGINE_A2A_SERVER_BASE_URL=http://qoc-agent-b:8080
ENGINE_A2A_SERVER_AGENT=none
ENGINE_A2A_URLS=
ENGINE_MCP_SERVER_ENABLED=false
```

### 4.4 Container C – `mcp-server`

Option A: QOC als MCP-Server (`ENGINE_MCP_SERVER_ENABLED=true`, sonst
deaktiviert).  
Option B: ein minimaler MCP-Referenzserver (z. B. Python `mcp` SDK) mit Tool
`echo(text)`. Option B ist für einen reinen Protokolltest ausreichend und
macht die Suite unabhängig von QOC-eigenen MCP-Server-Details.

**Empfehlung:** Option B für den Protokolltest, Option A für den
QOC-MCP-Server-Test (separater Lauf).

## 5. Docker-Compose (Skizze)

```yaml
services:
  qoc-agent-a:
    image: qoc-engine:local
    container_name: qoc-agent-a
    environment:
      - ENGINE_A2A_SERVER_ENABLED=true
      - ENGINE_A2A_SERVER_BASE_URL=http://qoc-agent-a:8080
      - ENGINE_A2A_ENDPOINTS=id=agent-b|url=http://qoc-agent-b:8080|tool=a2a_agent_b|enabled=true
      - ENGINE_MCP_SERVER_ENABLED=true
      - ENGINE_SECURITY_MODE=basic
      - OPENCODE_SERVER_PASSWORD=interop-secret
      - ENGINE_PROVIDER_SCRIPTED=true
    volumes:
      - ./interop/settings-a.json:/data/workspace/settings.json:ro
    ports: ["8080:8080"]
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/q/health/ready"]
      interval: 10s
      timeout: 5s
      retries: 20

  qoc-agent-b:
    image: qoc-engine:local
    container_name: qoc-agent-b
    environment:
      - ENGINE_A2A_SERVER_ENABLED=true
      - ENGINE_A2A_SERVER_BASE_URL=http://qoc-agent-b:8080
      - ENGINE_SECURITY_MODE=basic
      - OPENCODE_SERVER_PASSWORD=interop-secret
      - ENGINE_PROVIDER_SCRIPTED=true
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/q/health/ready"]
      interval: 10s
      timeout: 5s
      retries: 20

  mcp-server:
    image: python:3.12-alpine
    container_name: mcp-server
    command: ["python", "/app/server.py"]
    volumes:
      - ./interop/mcp_server.py:/app/server.py:ro
    ports: ["8090:8090"]
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8090/health"]
      interval: 10s
      timeout: 5s
      retries: 20
```

## 6. Protokoll-Flüsse

### 6.1 A2A: A → B

1. A liest Agent-Card von B: `GET http://qoc-agent-b:8080/.well-known/agent-card.json`
2. A sendet `SendMessage` (JSON-RPC, Methode `SendMessage`) mit Basic Auth
   (`opencode`/`interop-secret`).
3. B erzeugt eine QOC-Session, scripted Provider antwortet, B emittiert ein
   Artifact mit Text.
4. A empfängt den Task mit `status.state = TASK_STATE_COMPLETED` und liest
   `artifacts[0].parts[0].text`.

### 6.2 MCP: A → C

1. A initialisiert den MCP-Client gegen `http://mcp-server:8090/mcp`
   (`initialize`, `tools/list`).
2. A ruft `tools/call` mit `echo`/`qoc_run_agent` auf.
3. C antwortet mit `content` (Text) bzw. `structuredContent`.

### 6.3 Kombi: A nutzt beide Protokolle in einem Turn

1. A erhält einen Prompt „Fasse die Antwort von B und das Echo von C zusammen".
2. A ruft das A2A-Tool `a2a_agent_b` auf (→ B).
3. A ruft das MCP-Tool `mcp_echo` auf (→ C).
4. A kombiniert beide Ergebnisse in der finalen Antwort.

Da der scripted Provider keine echten Tool-Calls emittiert, wird diese
Kombination als **manueller Test** (curl/SDK) oder über einen Test-Runner mit
SDK-Client ausgeführt, nicht über den LLM-Pfad.

## 7. Testmatrix

### A2A-Szenarien

| ID | Given | When | Then |
|---|---|---|---|
| A2A-01 | B läuft, A konfiguriert | `GET A/.well-known/agent-card.json` | 200, Name `Agent A`, JSON-RPC-Interface |
| A2A-02 | B läuft | `GET B/.well-known/agent-card.json` ohne Credentials | 200 (Card ist öffentlich) |
| A2A-03 | Basic Auth aktiv | `POST B/` `SendMessage` ohne Credentials | 401 |
| A2A-04 | Basic Auth aktiv | `POST B/` `SendMessage` mit falschem Passwort | 401 |
| A2A-05 | Basic Auth aktiv | `POST B/` `SendMessage` mit korrekten Credentials | 200, `result.kind == "task"`, `status.state == "TASK_STATE_COMPLETED"`, Text enthält „Session Engine" |
| A2A-06 | B gestoppt | A ruft Discovery `GET A/api/a2a/agents` auf | `data[0].status == "error"` |
| A2A-07 | B wieder gestartet | `POST A/api/a2a/agents/refresh` | `data[0].status == "ok"`, `cached == false` |
| A2A-08 | A2A-Server deaktiviert (`ENGINE_A2A_SERVER_ENABLED=false`) | `GET A/.well-known/agent-card.json`, `POST A/` | 404 |
| A2A-09 | Zwei User (Basic: `opencode`/`interop-secret`) | User A erzeugt Task, User B ruft `tasks/get` auf | 403 (Task-Ownership) |
| A2A-10 | Cache TTL 2 s | Zwei `GET` innerhalb TTL, dann 3 s warten | erster/ zweiter `cached == true`, dritter `cached == false` |

### MCP-Szenarien

| ID | Given | When | Then |
|---|---|---|---|
| MCP-01 | C läuft | A `tools/list` über MCP | `echo` (oder `qoc_run_agent`) vorhanden |
| MCP-02 | C läuft | A `tools/call` mit `echo` | Antwort enthält den gesendeten Text |
| MCP-03 | C gestoppt | A ruft MCP-Tool auf | Fehler, andere Server unberührt (Isolation) |
| MCP-04 | OAuth deaktiviert | `GET A/mcp/authorize` | 503 |
| MCP-05 | A als MCP-Server aktiv | externer Client `tools/list` gegen A | genau ein Tool: `qoc_run_agent` |
| MCP-06 | A als MCP-Server aktiv | externer Client ruft `qoc_run_agent` mit `agent=agent-a`, `prompt=Hallo` auf | Antwort enthält „Session Engine" und `_meta.sessionId` |

### Kombi-Szenarien

| ID | Given | When | Then |
|---|---|---|---|
| COMBO-01 | A, B, C laufen | A führt A2A-`SendMessage` an B und MCP-`tools/call` an C sequenziell aus | beide Antworten korrekt, keine Vermischung von Kontexten |
| COMBO-02 | A konfiguriert beide Protokolle | `GET A/api/a2a/agents` | nur B gelistet (MCP-Server sind nicht Teil der A2A-Discovery) |
| COMBO-03 | A als A2A- und MCP-Server aktiv | externer Client nutzt beide Endpunkte (`/.well-known/agent-card.json`, `/mcp`) | beide antworten, keine gegenseitige Blockade |

## 8. Automatisierung

- **Build:** `deploy/build-image.sh` erzeugt `qoc-engine:local`.
- **Runner:** Container `a2a-mcp-test` (Alpine + curl + jq + Python) führt
  `run.sh` aus und beendet sich mit Exit-Code 0/1.
- **A2A-Payloads:** SDK-Format (Methode `SendMessage`, `GetTask`), generiert
  aus `org.a2aproject.sdk` oder als feste JSON-Dateien unter
  `interop/payloads/`.
- **MCP-Payloads:** JSON-RPC 2.0 `initialize`, `tools/list`, `tools/call`.
- **Logs:** `docker compose logs` nach jedem Lauf; bei Fehler `docker compose down -v`
  und Neustart.

## 9. Abnahmekriterien

- Alle Szenarien A2A-01…A2A-10, MCP-01…MCP-06, COMBO-01…COMBO-03 grün.
- Keine Secrets in Logs (`interop-secret` darf nicht in Klartext erscheinen).
- Container A überlebt das Stoppen von B und C (Isolation).
- Discovery-Cache wird bei Refresh neu geladen.
- MCP-Server exponiert genau `qoc_run_agent` (M-01).
- A2A-Server ist default deaktiviert (404) und nur mit Flag aktiv.

## 10. Evidenz (Mapping zu Regeln)

| Regel | Test-ID | Art | Status |
|---|---|---|---|
| A2A-Client-Egress | A2A-01, A2A-05 | Integration | open |
| A2A-Server opt-in (404) | A2A-08 | Integration | open |
| A2A-Auth (Basic) | A2A-03, A2A-04, A2A-05 | Integration | open |
| A2A-Task-Ownership | A2A-09 | Integration | open |
| A2A-Cache | A2A-10 | Integration | open |
| A2A-Discovery | A2A-06, A2A-07 | Integration | open |
| MCP-M-01 (genau ein Tool) | MCP-05 | Integration | open |
| MCP-M-04 (OAuth inaktiv → 503) | MCP-04 | Integration | open |
| MCP-M-05 (Delegation an Engine) | MCP-06 | Integration | open |
| Interop-Isolation | A2A-06, MCP-03, COMBO-01 | Integration | open |

## 11. Grenzen

- Kein echter LLM-Pfad: Tool-Calls werden über SDK/HTTP direkt ausgelöst, nicht
  über den Modell-Loop. Der Modell-Loop mit echten Tool-Calls bleibt in den
  JVM-Tests (`A2AServerTest`).
- Kein OIDC in diesem Konzept; OIDC läuft als separates Profil mit Keycloak
  (`docs/plans/e2e-security-test.md` §4, §8).
- Kein Performance-Test; Timeouts sind Sanity-Checks, keine Benchmarks.
- Container C kann entweder QOC-MCP-Server oder Referenz-MCP-Server sein;
  die Ergebnisse sind nicht 1:1 vergleichbar.
EOF
echo "written"; wc -l docs/spec/qualitaet/04-docker-a2a-mcp-e2e.md
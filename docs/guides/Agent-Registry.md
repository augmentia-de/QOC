# Agent Registry (Option A)

Status: optional, implementiert (Registry-Rolle in QOC)
Datum: 2026-11-04

Diese Variante betreibt QOC **nur als Agent-Registry** – ohne Ausführung von
Prompts. Sie ist eine optionale Nutzungsart; der normale Server-/Serverless-
Betrieb bleibt unverändert.

## 1. Zweck

Eine oder mehrere QOC-Instanzen stellen den Agent-Katalog zentral bereit:

- Agenten lesen (`GET /api/agent`, `GET /api/agent/{name}`)
- Agenten anlegen/ändern/löschen (`POST/PUT/DELETE /api/agent`)
- Serverless-Worker und VM-Worker holen ihre Agent-Config von dort
- Kein Prompt-, Session- oder MCP-Tool-Betrieb auf der Registry

Das ist der kleinste Schritt in Richtung eines eigenständigen Agent-Registry-
Service: Die Registry nutzt die vorhandene QOC-API und -Serialisierung, führt
aber nichts aus.

## 2. Architektur

```
Editor / Admin
      │  POST/PUT/DELETE /api/agent
      ▼
QOC Registry (ENGINE_ROLE=registry)
      │  GET /api/agent/{name}   (config, hash, updatedAt)
      ├──────────────┬───────────────────┐
      ▼              ▼                   ▼
 QOC Serverless   QOC Worker (VM)     QOC Server
 (Fetch pro Call) (optional Fetch)    (optional Cache)
```

Die Registry ist **kein** MCP-Server und **kein** Executor. Sie liefert nur
Agent-Definitionen.

## 3. Konfiguration

```env
ENGINE_MODE=server
ENGINE_ROLE=registry
ENGINE_AGENT_AUTHORING_ENABLED=true
ENGINE_SECURITY_MODE=oidc
```

Wichtig:

- `ENGINE_MODE=server` (nicht `serverless`) – sonst würden Workspace-Agenten
  ignoriert und Authoring wäre gesperrt.
- `ENGINE_ROLE=registry` disables Ausführung, nicht das Authoring.
- `ENGINE_AGENT_AUTHORING_ENABLED=true` muss gesetzt bleiben.

### Docker (optional)

```bash
cd deploy
docker compose up -d qoc-registry
```

Der Service nutzt das Profil `e2e` und ist fest auf Port `8074` gemappt
(`ENGINE_MODE=server`, `ENGINE_ROLE=registry`, OIDC gegen Keycloak).
Mit dem OIDC-Setup ist Keycloak Voraussetzung (`deploy/start-oidc.sh`).

Die Registry läuft dann unter `http://localhost:8074`.

## 4. API

| Methode | Pfad | Zweck |
|---|---|---|
| `GET` | `/api/agent` | Liste (`id`, `name`, `hidden`) |
| `GET` | `/api/agent/{name}` | `{name, content?, config, hash?, updatedAt?}` |
| `POST` | `/api/agent` | Agent aus Markdown anlegen (`qoc-admin`) |
| `PUT` | `/api/agent/{name}` | Agent aktualisieren (`qoc-admin`) |
| `DELETE` | `/api/agent/{name}` | Agent löschen (`qoc-admin`) |
| `GET` | `/api/health` | u. a. `role=registry`, `mode=server` |

Nicht verfügbar (HTTP 503, `_tag=RegistryMode`):

- `POST /api/run`
- `POST /api/session`
- `POST /api/session/{id}/resume`
- `POST /api/session/{id}/prompt`
- `POST /api/session/{id}/command`
- MCP `tools/call` (`/mcp`)
- A2A-Ausführung (Executor lehnt ab)

## 5. Konsumenten

Serverless-Worker oder VM-Worker zeigen auf die Registry:

```env
ENGINE_AGENT_SOURCE_URL=http://host.docker.internal:8074
# optional:
ENGINE_AGENT_SOURCE_TOKEN=...
```

Der Fetch nutzt weiterhin:

```text
GET {ENGINE_AGENT_SOURCE_URL}/api/agent/{name}
Authorization: Bearer <Token des aufrufenden MCP-Clients>
```

Damit ist die Registry der einzige Config-Lieferant; QOC-Runtimes führen nur
aus.

## 6. Rollen und Sicherheit

- Authoring erfordert die Rolle `admin` oder `qoc-admin`
  (`HttpApi.hasRole` akzeptiert beide).
- `qoc-admin` wird im lokalen Keycloak-Setup an `bob` vergeben.
- OIDC-Audience weiterhin `qoc-engine`.
- Audit-Log für Agent-Writes ist vorhanden (`/api/audit`, `admin`).
- Die Registry sollte **nicht** öffentlich ohne OIDC betrieben werden.

## 7. Grenzen dieser Variante

- Agent-Definitionen liegen weiterhin dateibasiert unter
  `~/.config/opencode/agents` und in `settings.json`.
- Keine Versionierung/ETag pro Agent (nur `hash`/`updatedAt`).
- Keine eigene Editor-UI; die vorhandene QOC-UI wird mitgenutzt.
- Keine Mandantentrennung über die bestehende Workspace-/User-Logik hinaus.
- Einzelinstanz; keine HA-/DB-Versionierung.

## 8. Ausbaupfad zum eigenständigen Service

1. `AgentSource`-Port in QOC einführen (lokal + remote).
2. Registry-Read-API als eigenständigen Dienst betreiben (Option B).
3. Versionierung/ETag/If-None-Match ergänzen.
4. Authoring-UI aus QOC herauslösen.
5. Optional: Git-backed Registry (Option C).

## 9. Verifikation

```bash
TOKEN=$(curl -s -X POST http://localhost:8180/realms/qoc/protocol/openid-connect/token \
  -d grant_type=password -d client_id=qoc-cli \
  -d username=bob -d password=bob-password | jq -r .access_token)

# Katalog
curl -H "Authorization: Bearer $TOKEN" http://localhost:8074/api/agent

# Agent lesen
curl -H "Authorization: Bearer $TOKEN" http://localhost:8074/api/agent/plan

# Ausführung muss 503 liefern
curl -i -X POST http://localhost:8074/api/run \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"prompt":"test"}'
```

## 10. Tests

- `RegistryModeTest`: Health zeigt `role=registry`; `/api/run` und
  `/api/session` liefern 503; `/api/agent` bleibt verfügbar; MCP `tools/call`
  liefert 503.
- Bestehende Server-/Serverless-Tests bleiben unverändert.

## 11. Referenzen

- `quarkus/src/main/java/de/augmentia/oc/engine/http/EngineConfig.java`
  (`engine.role`)
- `quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java`
  (`registryBlocked`, Agent-Endpunkte)
- `quarkus/src/main/java/de/augmentia/oc/engine/http/McpServerResource.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/a2a/QocAgentExecutor.java`
- `deploy/docker-compose.yml` (Service `qoc-registry`, Profil `e2e`)
- `docs/guides/deployment-modes.md`

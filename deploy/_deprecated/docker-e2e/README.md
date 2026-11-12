# E2E-Testvarianten (Port 8073 / 8074 / 8078 / 8079)

E2E-Rollen und Interop-Tests. Alle Definitionen liegen in
`deploy/docker-compose.yml`; dieser Ordner enthält keine eigenen Compose-Dateien
mehr.

## Server-Rolle (GCP-VM-Äquivalent) – Port 8073

```bash
cd deploy
docker compose up -d qoc-server
```

- `ENGINE_MODE=server`, `ENGINE_AGENT_AUTHORING_ENABLED=true`
- OIDC gegen Keycloak (`http://localhost:8180/realms/qoc`)
- UI: http://localhost:8073/

## Agent-Registry – Port 8074

```bash
cd deploy
docker compose up -d qoc-registry
```

- `ENGINE_ROLE=registry`: nur Agent-Katalog und Authoring-API
- Ausführungs-Endpunkte (Sessions, Prompts, MCP-Tool-Calls, A2A) antworten mit 503
- Serverless-Worker nutzen `ENGINE_AGENT_SOURCE_URL=http://host.docker.internal:8074`

## A2A/MCP-Interop – Port 8078 / 8079

Hermetischer Test mit SQLite, scripted Provider und zwei Agenten:

```bash
cd deploy
./build-image-sqlite.sh      # erzeugt qoc-engine:sqlite
docker compose --profile a2a up -d
```

| Service | Host-Port | Zweck |
|---|---|---|
| `qoc-agent-a` | 8078 | A2A-Server, ruft Agent B über `a2a_agent_b` |
| `qoc-agent-b` | – | A2A-Server ohne ausgehende A2A-Aufrufe |
| `mcp-server` | 8079 | Python-MCP-Testserver |

- Basic Auth: `OPENCODE_SERVER_PASSWORD=interop-secret`
- `ENGINE_PROVIDER_SCRIPTED=true` (kein echtes LLM)
- SQLite-Dateien liegen in den Named Volumes `qoc-a-data` / `qoc-b-data`

## Hinweise

- Alle Rollen teilen dieselbe Basis und dasselbe Image (`qoc-engine:local`),
  nur die A2A-Services nutzen `qoc-engine:sqlite`.
- Keycloak muss für die OIDC-Rollen laufen (`deploy/start-oidc.sh`).

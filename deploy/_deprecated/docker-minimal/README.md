# Minimal / Serverless (Port 8072)

Serverless-ähnlicher Stack für lokale Tests: ephemerer Workspace und ephemeres
HOME, Agenten kommen aus der Agent-Registry. Die Definition liegt in
`deploy/docker-compose.yml` (Profil `minimal`).

## Start

```bash
cd deploy
docker compose up -d qoc-minimal
```

- UI: http://localhost:8072/
- Voraussetzung: Keycloak läuft (`deploy/keycloak`) und optional die Registry
  (`docker compose up -d qoc-registry`).

## Eigenschaften

- `ENGINE_MODE=serverless`
- `ENGINE_AGENT_AUTHORING_ENABLED=false` – nur Katalog-Agenten
- `ENGINE_AGENT_SOURCE_URL=http://host.docker.internal:8074` (Registry)
- `ENGINE_MAX_CONCURRENT_PROMPTS=1`, `ENGINE_TURN_MAX_WALL_MINUTES=10`
- `ENGINE_SECURITY_MODE=oidc` gegen das lokale Keycloak
- `/data/workspace`, `/data/home` und `/data/log` sind tmpfs: nach einem
  Neustart ist alles weg. Es gibt **keine** Host-Mounts.

## Hinweise

- Das ist keine eigene Basis mehr, sondern eine Rolle derselben Basis wie
  `qoc-local` – nur mit anderem Workspace- und Agenten-Setup.
- Registry-Server (optional) auf http://localhost:8074.

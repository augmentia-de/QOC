# Gehärtetes Deployment (Port 8075)

Produktionsnahe Variante ohne Hot-Reload und ohne Quellcode-Mount. Die
Definition liegt in `deploy/docker-compose.yml` (Profil `secure`).

## Start

```bash
cd deploy
docker compose up -d qoc-secure
```

- UI: http://localhost:8075/
- Health: http://localhost:8075/api/health/ready

## Konfiguration

| Einstellung | Wert |
|---|---|
| `ENGINE_PERMISSION_DEFAULT` | `least-privilege` |
| `ENGINE_PERMISSION_ASK_MODE` | `deny` |
| `ENGINE_AGENT_AUTHORING_ENABLED` | `false` |
| `ENGINE_SETTINGS_WORKSPACE_ENABLED` | `false` |
| `ENGINE_EMAIL_ENABLED` / `ENGINE_EMAIL_READ_ENABLED` | `false` |
| Shell-Sandbox | `false` (Host erlaubt keine Namespaces) |

## Isolation

- read-only Container-Root, `/data` und `/tmp` als tmpfs
- `cap_drop: ALL`, `no-new-privileges`, `pids_limit: 256`
- kein Quellcode-Mount, kein Maven-Cache, kein Docker-Socket
- nur zwei Bind-Mounts:
  - `deploy/docker-secure/workspace` → `/data/workspace`
  - `deploy/docker-secure/home` → `/data/home`
- Log- und LLM-Trace-Dateien sind deaktiviert; Logs gehen nach `docker logs`

## Unterschied zu `qoc-secure-shell`

`qoc-secure` hat weiterhin einen Workspace **und** ein HOME. Die Variante
`qoc-secure-shell` (Port 8076) mountet dagegen ausschließlich `/data/home`,
damit die Shell keine anderen Datenpfade erreichen kann.

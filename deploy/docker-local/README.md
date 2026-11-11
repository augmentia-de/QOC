# Lokaler Server-Stack (Port 8071)

Vollständiger Single-Node-Stack mit Postgres, optional OIDC. Die Definition
liegt in `deploy/docker-compose.yml` (Profil `local`).

## Start

```bash
cd deploy
cp .env.example .env      # Werte anpassen
docker compose up -d qoc-local
```

- UI: http://localhost:8071/
- Health: http://localhost:8071/api/health/ready

## OIDC-Overlay

```bash
docker compose -f docker-compose.yml -f docker-compose.oidc.yml up -d qoc-local
```

## Mounts

| Host | Container | Zweck |
|---|---|---|
| `${QOC_WORKSPACE_HOST:-../data/workspace}` | `/data/workspace` | Agent-Workspace |
| `../data/home` | `/data/home` | HOME, Pläne, globale Agenten |
| `../data/qoc.db` | `/data/qoc.db` | SQLite-Datei (optional) |
| `../log` | `/data/log` | Anwendungslog |
| `AGENTS.jvm.md` | `/data/workspace/AGENTS.jvm.md` | JVM-spezifische Instruktionen (ro) |
| `${QOC_SKILLS_HOST:-../data/skills/aisdlc}` | `/data/skills/aisdlc` | Skill-Bibliothek (ro) |

Für den bisherigen Workspace `dws`:

```bash
QOC_WORKSPACE_HOST=/home/torsten/dev/my-projects/2oc/dws docker compose up -d qoc-local
```

## Härtung

- read-only Container-Root, `/data` und `/tmp` als tmpfs
- `cap_drop: ALL`, `no-new-privileges`, `pids_limit: 256`
- Prozess läuft als Host-UID/GID, nicht als root
- keine breiten `/data`-Mounts

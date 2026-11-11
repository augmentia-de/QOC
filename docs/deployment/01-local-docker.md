# Lokale Docker- und Native-Deployment

> **Status:** implemented
> **Quelle:** `deploy/README.md`, `deploy/docker-compose.yml`, `deploy/Dockerfile`,
> `deploy/Dockerfile.native`

Alle Varianten liegen in **einer** Datei (`deploy/docker-compose.yml`) und haben
ein eigenes Profil sowie einen festen Host-Port:

| Service | Profil | Port |
|---|---|---|
| `qoc-dev` | `dev` | 8070 |
| `qoc-local` | `local` | 8071 |
| `qoc-minimal` | `minimal` | 8072 |
| `qoc-server` | `e2e` | 8073 |
| `qoc-registry` | `e2e` | 8074 |
| `qoc-secure` | `secure` | 8075 |
| `qoc-secure-shell` | `secure-shell` | 8076 |
| `qoc-native` | `native` | 8077 |
| `qoc-agent-a` / `mcp-server` | `a2a` | 8078 / 8079 |

## JVM (Docker Compose)

```bash
cd qoc
cp deploy/.env.example deploy/.env
# .env anpassen
./deploy/build-image.sh
cd deploy
docker compose up -d qoc-local
```

- Image: `qoc-engine:local`
- Port: `8071` (fest, kein `QOC_PORT` mehr)
- Datenbank: PostgreSQL (extern, z. B. Neon) oder SQLite
- Mounts: `workspace`, `home`, `qoc.db`, `log`
- Ohne Dienstnamen startet nichts, weil alle Services Profile haben

## Native (Docker Compose)

```bash
cd qoc
ENGINE_DB_KIND=postgresql ENGINE_DB_DRIVER=org.postgresql.Driver \
  QUARKUS_FLYWAY_LOCATIONS=classpath:db/migration \
  mvn -DskipTests package -Dnative
cd deploy
docker compose --profile native up -d --build
```

- Image: `qoc-engine:native`
- Port: `8077`
- Enthält git + util-linux (Worktree-Tools, PTY)
- `AGENTS.native.md` wird read-only gemountet

## Native (ohne Docker)

```bash
cd qoc
cp deploy/.env.example deploy/.env
./start-native.sh
```

- Lauscht auf `${QUARKUS_HTTP_PORT:-8007}`
- Fallback auf SQLite wenn keine JDBC-URL konfiguriert

## Shell-Isolation

`qoc-secure-shell` (Port 8076) mountet ausschließlich `/data/home`:
andere Datenpfade (`/data/workspace`, `/data/qoc.db`, `../log`) existieren im
Container nicht. Details: `deploy/docker-secure-shell/README.md`.

## Hardening

Siehe `06-security-hardening.md`. Kurzfassung:
- Read-only Root-Dateisystem
- Targeted Mounts (kein ganzes `/data`)
- `cap_drop ALL`, `no-new-privileges`, `pids_limit: 256`
- bwrap deaktiviert in Docker (`ENGINE_SHELL_SANDBOX_ENABLED=false`)

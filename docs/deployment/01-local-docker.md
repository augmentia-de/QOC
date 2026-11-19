# Local Docker and Native Deployment

> **Status:** implemented
> **Source:** `deploy/README.md`, `deploy/docker-compose.yml`, `deploy/Dockerfile`,
> `deploy/Dockerfile.native`

All variants live in **one** file (`deploy/docker-compose.yml`) and each has
its own profile as well as a fixed host port:

| Service | Profile | Port |
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
# adjust .env
./deploy/build-image.sh
cd deploy
docker compose up -d qoc-local
```

- Image: `qoc-engine:local`
- Port: `8071` (fixed, no `QOC_PORT` anymore)
- Database: PostgreSQL (external, e.g. Neon) or SQLite
- Mounts: `workspace`, `home`, `qoc.db`, `log`
- Nothing starts without a service name, because all services have profiles

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
- Contains git + util-linux (worktree tools, PTY)
- `AGENTS.native.md` is mounted read-only

## Native (without Docker)

```bash
cd qoc
cp deploy/.env.example deploy/.env
./start-native.sh
```

- Listens on `${QUARKUS_HTTP_PORT:-8007}`
- Falls back to SQLite when no JDBC URL is configured

## Shell Isolation

`qoc-secure-shell` (Port 8076) mounts only `/data/home`:
other data paths (`/data/workspace`, `/data/qoc.db`, `../log`) do not exist in
the container. Details: `deploy/docker-secure-shell/README.md`.

## Hardening

See `06-security-hardening.md`. Summary:
- Read-only root filesystem
- Targeted mounts (no whole `/data`)
- `cap_drop ALL`, `no-new-privileges`, `pids_limit: 256`
- bwrap disabled in Docker (`ENGINE_SHELL_SANDBOX_ENABLED=false`)
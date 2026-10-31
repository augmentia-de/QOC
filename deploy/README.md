# QOC – minimal local/Docker deployment

Build and run locally:

```bash
cd qoc
cp deploy/.env.example deploy/.env
# edit deploy/.env
./deploy/build-image.sh
cd deploy
docker compose up -d
```

`build-image.sh` builds the fast-jar with the PostgreSQL database kind and
driver (both are build-time settings in Quarkus), then builds the image.
`deploy/.env.example` shows the required JDBC URL and credentials. The default
local setup targets the host-published PostgreSQL/pgvector container:

```properties
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://host.docker.internal:20000/qoc?sslmode=disable
QUARKUS_DATASOURCE_USERNAME=postgres
QUARKUS_DATASOURCE_PASSWORD=postgres
```

The compose file adds `host.docker.internal:host-gateway`, so the QOC container
can reach a database published on the host at port `20000`. For Neon or another
external database, replace the URL and credentials in `deploy/.env`.

Tests also leave a test-profile build in `quarkus/target/quarkus-app`, so run
`build-image.sh` after `mvn verify` before recreating the container.

Open: http://127.0.0.1:${QOC_PORT:-8333}/

The `qoc-data` Docker volume stores the workspace, snapshots, and tool output.
Plan mode files are written to `HOME` (`/data/home`), i.e.
`/data/home/.opencode/plan` inside the volume. The compose file sets `HOME`
explicitly because the container user has no `/etc/passwd` entry, which would
otherwise make Java fall back to `/` and break plan writes.

The JVM compose file mounts `../log` at `/data/log`. The application writes
rotated DEBUG logs to `<repo>/log/qoc.log` on the host and also emits DEBUG
messages to `docker logs qoc-engine`. The logging settings are:

```properties
quarkus.log.level=DEBUG
quarkus.log.console.level=DEBUG
quarkus.log.file.enabled=true
quarkus.log.file.level=DEBUG
quarkus.log.file.path=/data/log/qoc.log
```

The JVM compose setup disables pre-turn revert snapshots with
`ENGINE_REVERT_SNAPSHOT=false`. Snapshot capture wrote every workspace file to
the remote database before each model call, which added several seconds of
latency. Set it to `true` only when file-level `revert` is required.

### SQLite alternative

SQLite remains supported for single-node deployments. Set the SQLite values
from the commented block in `deploy/.env.example`, then build the SQLite image:

```bash
./deploy/build-image-sqlite.sh
```

`build-image-sqlite.sh` produces `qoc-engine:sqlite`. The hermetic A2A/MCP E2E
stack uses that tag directly. If you want the main compose stack on SQLite,
retag it after setting the SQLite values in `.env`:

```bash
./deploy/build-image-sqlite.sh
docker tag qoc-engine:sqlite qoc-engine:local
cd deploy
docker compose up -d
```

The E2E stack runs with:

```bash
./deploy/build-image-sqlite.sh
cd deploy
docker compose -f docker-compose.a2a-mcp-e2e.yml up -d
```

## Native image

```bash
cd qoc
ENGINE_DB_KIND=postgresql ENGINE_DB_DRIVER=org.postgresql.Driver \
  QUARKUS_FLYWAY_LOCATIONS=classpath:db/migration \
  mvn -DskipTests package -Dnative     # requires GraalVM
cd deploy
docker compose -f docker-compose.native.yml up -d --build
```

A native binary built for SQLite must be rebuilt with the PostgreSQL settings
above before it can use the default `.env` (external PostgreSQL). The native
image intentionally contains **no git**. The worktree tools are
therefore unavailable there; use the JVM image for git worktrees.

The native compose file mounts `deploy/AGENTS.native.md` read-only at
`/data/workspace/AGENTS.md`, so agents see the native-specific restrictions
without modifying the shared host workspace. The file is also baked into the
image at `/opt/qoc/AGENTS.md`.

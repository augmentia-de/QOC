# QOC – Docker-Deployments (konsolidiert)

Alle Varianten teilen **eine** Compose-Datei (`deploy/docker-compose.yml`) und
**eine** Umgebung (`deploy/.env`). Jede Variante ist ein Service mit eigenem
Profil und eigenem Host-Port:

| Service | Profil | Port | Zweck |
|---|---|---|---|
| `qoc-dev` | `dev` | 8070 | Hot-Reload-Entwicklung |
| `qoc-dev-native` | `dev-native` | 8081 | Native-Entwicklung (Mandrel, Rebuild beim Start) |
| `qoc-local` | `local` | 8071 | lokaler Server-Stack (Postgres) |
| `qoc-memory` | `memory` | 8082 | In-Memory-Storage, keine Datenbank |
| `qoc-minimal` | `minimal` | 8072 | serverless-Rolle, ephemerer Workspace |
| `qoc-server` | `e2e` | 8073 | E2E-Server-Rolle (GCP-VM-Äquivalent) |
| `qoc-registry` | `e2e` | 8074 | E2E-Agent-Registry |
| `qoc-secure` | `secure` | 8075 | gehärtet, Least-Privilege |
| `qoc-secure-shell` | `secure-shell` | 8076 | Shell sieht nur `/data/home` |
| `qoc-native` | `native` | 8077 | Native-Image |
| `qoc-agent-a` | `a2a` | 8078 | A2A-Interop-Agent A |
| `mcp-server` | `a2a` | 8079 | MCP-Testserver |

Start einer Variante:

```bash
cd qoc
cp deploy/.env.example deploy/.env
# deploy/.env bearbeiten (API-Key, DB)
./deploy/build-image.sh          # baut das Fast-Jar und qoc-engine:local
cd deploy
docker compose up -d qoc-local
```

Weil alle Services Profile haben, startet `docker compose up -d` ohne
Dienstnamen nichts – es wird immer genau eine Variante gestartet.

Details je Variante: `deploy/docker-<variante>/README.md`.

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

Open: http://127.0.0.1:8071/

### Lokale Entwicklungsumgebung (Docker Dev)

Vollwertige Dev-Umgebung mit Hot-Reload, Debug-Port und Testcontainers:

```bash
cd deploy
cp .env.example .env   # Werte anpassen (DB auf host.docker.internal:20000)
docker compose up -d --build qoc-dev
# UI: http://localhost:8070/
# Debug: localhost:5006
```

Siehe `deploy/docker-dev/README.md`.

## Local default mode

By default the UI runs **OIDC-free**: no issuer, no login button, no discovery
request. The engine defaults to `ENGINE_SECURITY_MODE=none` (or `basic` when
`OPENCODE_SERVER_PASSWORD` is set).

To use local Keycloak or Auth0 locally, enable OIDC explicitly, for example in
the browser console:

```js
localStorage.setItem('qoc.oidcEnabled', 'true');
localStorage.setItem('qoc.issuer', 'http://localhost:8180/realms/qoc');
localStorage.setItem('qoc.clientId', 'qoc-ui');
location.reload();
```

Or serve a deployment-specific `/config.js` (see `deploy/gcp-vm/gcp-auth0.sh`).

The `qoc-data` Docker volume stores the workspace, snapshots, and tool output.
Plan mode files are written to `HOME` (`/data/home`), i.e.
`/data/home/.opencode/plan` inside the volume. The compose file sets `HOME`
explicitly because the container user has no `/etc/passwd` entry, which would
otherwise make Java fall back to `/` and break plan writes.

The JVM compose file mounts `../log` at `/data/log`. The application writes
rotated DEBUG logs to `<repo>/log/qoc.log` on the host and also emits DEBUG
messages to `docker logs qoc-local`. The logging settings are:

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
docker compose up -d qoc-local
```

The E2E stack runs with:

```bash
./deploy/build-image-sqlite.sh
cd deploy
docker compose --profile a2a up -d
```

## In-Memory Runtime (no database)

`qoc-memory` runs the full engine with all storage ports backed by in-memory
adapters. There is no database, no migration, and no restart recovery: the
state lives in the process heap and disappears when the container stops.

```bash
cd qoc
./deploy/build-image.sh
cd deploy
docker compose --profile memory up -d qoc-memory
```

- UI: http://localhost:8082/
- Storage: `ENGINE_STORAGE=memory`, `ENGINE_RECOVERY_ENABLED=false`
- Limits: `ENGINE_MEMORY_MAX_BYTES` (default 1 GiB)
- Workspace, home, and log are tmpfs; the whole container is ephemeral.
- `QUARKUS_DATASOURCE_JDBC_URL` is overridden with an empty value, so the
  datasource stays inactive even when `deploy/.env` contains PostgreSQL values.
- Revert snapshots are kept in memory and count against the memory budget.

Do not use this profile for multi-user production, HA, or audit requirements.
Details: `deploy/docker-memory/README.md`.

## Native image

```bash
cd qoc
ENGINE_DB_KIND=postgresql ENGINE_DB_DRIVER=org.postgresql.Driver \
  QUARKUS_FLYWAY_LOCATIONS=classpath:db/migration \
  mvn -DskipTests package -Dnative     # requires GraalVM
cd deploy
docker compose --profile native up -d --build
```

A native binary built for SQLite must be rebuilt with the PostgreSQL settings
above before it can use the default `.env` (external PostgreSQL). The native
image now includes **git** and **util-linux** (see `Dockerfile.native`), so
commit-per-step workflows (e.g. AISDLC) work in native mode. Worktree tools
are available as well.

The native compose file mounts `deploy/AGENTS.native.md` read-only at
`/data/workspace/AGENTS.md`, so agents see the native-specific restrictions
without modifying the shared host workspace. The file is also baked into the
image at `/opt/qoc/AGENTS.md`.

## GCP VM with Auth0 (native)

Die GCP-VM-Konfiguration (systemd, nginx, Auth0, TLS, Runbook) liegt
gebündelt unter `deploy/gcp-vm/` – siehe `deploy/gcp-vm/README.md`.

The GCP VM can use Auth0 instead of local Keycloak. QOC remains a pure OIDC
resource server and validates bearer tokens with Auth0 discovery/JWKS; no
Auth0 client secret is stored on the VM.

Auth0 requirements:

- SPA application `QOC UI` (Authorization Code + PKCE, public client)
- API/resource server with identifier `qoc-engine`
- SPA authorized for `qoc-engine` (scope `qoc:use`)
- `audience=qoc-engine` is sent by the UI

The native binary must be built with OIDC enabled (build-time setting):

```bash
cd qoc
export QUARKUS_OIDC_ENABLED=true
mvn -o -pl quarkus -am clean package -Dnative \
  -Dquarkus.profile=prod,native -Dquarkus.oidc.enabled=true -DskipTests
```

Configure the VM:

```bash
cd qoc
cp deploy/gcp-vm/gcp-auth0.env.example deploy/gcp-vm/gcp-auth0.env
set -a; . deploy/gcp-vm/gcp-auth0.env; set +a
./deploy/gcp-vm/gcp-auth0.sh check
./deploy/gcp-vm/gcp-auth0.sh apply
```

`gcp-auth0.sh` writes the public OIDC values to `/etc/qoc/env`, creates
`/etc/qoc/ui-config.js`, verifies that `/api/session` returns 401 without a
token, and then calls `gcp-ssl.sh` with `QOC_AUTH_MODE=oidc` so nginx has no
HTTP Basic Auth. The UI stays public so the browser can start the PKCE flow.

Details, credentials, rollback, and verification are in `deploy/gcp-vm/vm.md`.

## Agent registry (optional)

QOC can run as a pure agent registry (`ENGINE_ROLE=registry`): the agent
catalogue and authoring API stay available, while prompt/session execution and
MCP tool calls are rejected with HTTP 503.

```bash
cd deploy
docker compose up -d qoc-registry
```

Workers and serverless instances then use
`ENGINE_AGENT_SOURCE_URL=http://host.docker.internal:8074`.
Details: `docs/guides/Agent-Registry.md`.

## Secure runtime layout

The compose files no longer mount the whole `/data` tree. Only the directories
the engine actually needs are bind-mounted:

```yaml
volumes:
  - /home/.../workspace:/data/workspace
  - ../data/home:/data/home
  - ../data/qoc.db:/data/qoc.db   # SQLite only
  - ../log:/data/log
```

The container root filesystem is read-only (`read_only: true`), `/data` and
`/tmp` are tmpfs mounts (the targeted bind mounts below are mounted on top),
all capabilities are dropped, `no-new-privileges` is set, and the process count
is limited:

```yaml
read_only: true
tmpfs:
  - /tmp
cap_drop:
  - ALL
security_opt:
  - no-new-privileges:true
pids_limit: 128
```

`HOME=/data/home` stays inside the persistent volume so plan files
(`~/.opencode/plan`) survive container recreation and never fall back to `/`.

### Shell-Isolation (`qoc-secure-shell`)

`qoc-secure-shell` mountet bewusst nur ein Verzeichnis:

```yaml
volumes:
  - ./docker-secure-shell/home:/data/home
```

`/data/workspace`, `/data/qoc.db`, `../log`, das Quell-Repository und der
Docker-Socket sind im Container nicht vorhanden. Die Grenze ist damit der
Mount-Namespace und nicht nur eine Pfad-Allow-Liste; sie greift auch für
Skripte, `cd ..` oder Variablen. Zusätzlich blockiert eine Seccomp-Deny-Liste
(`deploy/seccomp-qoc-shell.json`) `ptrace`, `process_vm_*`, `mount`,
`pivot_root`, `chroot` und `unshare`.

Details und Grenzen: `deploy/docker-secure-shell/README.md`.

Secrets are supplied through `deploy/.env` (mode `0600`) or secret files; they
are not baked into the image. In the container the bwrap sandbox is disabled
(`ENGINE_SHELL_SANDBOX_ENABLED=false`) because this runtime cannot create user
namespaces; the boundary then rests on the path allow-list, non-root execution
and the targeted read-only mounts. Database credentials are only reachable through
the configured JDBC URL, and outbound network access should be restricted at
the host/firewall level to the LLM/database endpoints actually required.

## Local native run

```bash
cd qoc
cp deploy/.env.example deploy/.env   # adjust values
./start-native.sh
```

`start-native.sh` loads `.env`, sets `ENGINE_WORKSPACE`, `LOG_DIR`, `HOME`,
and falls back to SQLite when no JDBC URL is configured. For PostgreSQL, point
`.env` at the host database (default `localhost:20000`) and keep Flyway enabled.
The engine then listens on `${QUARKUS_HTTP_PORT:-8007}`.

## Container run

```bash
cd qoc
cp deploy/.env.example deploy/.env   # adjust values
./deploy/build-image.sh
cd deploy
docker compose up -d qoc-local
```

For the native image use `docker compose --profile native up -d`.
Both variants use the targeted mount layout and the hardening options above.

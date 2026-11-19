# CONFIG.md – Example Configurations

> Copy-paste configuration profiles for QOC.
> Basis: `quarkus/src/main/resources/application.properties` + environment variables.
> Every line is commented. Sections can be adopted individually into a `.env` or
> `application.properties`.
>
> **Validity:** values without a `${...}`-default are mandatory.
> Environment variables override properties (Quarkus standard).

---

## 0. Shared Base (for both profiles)

```properties
# --- HTTP ---
quarkus.http.port=8080                           # Listener port
quarkus.http.host=127.0.0.1                      # loopback only (nginx/TLS in front)
%test.quarkus.http.test-port=0                   # Tests: dynamic port

# --- Model / Provider (LangChain4j) ---
quarkus.langchain4j.openai.api-key=${OPENAI_API_KEY:demo-key}   # LLM key from ENV
quarkus.langchain4j.openai.chat-model.model-name=gpt-4o-mini     # Model
quarkus.langchain4j.openai.chat-model.temperature=0.2           # Sampling

# --- Engine Provider (HTTP adapter) ---
engine.provider.type=http                        # http | scripted
engine.provider.scripted=true                    # true = no real LLM call (Tests/Dev)
engine.provider.url=${OPENAI_BASE_URL:https://api.openai.com/v1}/chat/completions
engine.provider.api-key=${OPENAI_API_KEY:demo-key}
engine.model.provider=openai                     # Provider ID
engine.model.id=${OPENAI_MODEL:gpt-4o-mini}      # Model ID

# --- Model limits ---
engine.model.context-limit=512000                # Context window (tokens)
engine.model.output-limit=128000                 # Max output tokens

# --- Workspace ---
engine.workspace=${ENGINE_WORKSPACE:.}            # Base directory
engine.session.temp-dir=${ENGINE_SESSION_TEMP_DIR:sessions}  # for ephemeral sessions
engine.settings.workspace-enabled=true            # Allow workspace settings

# --- Provider retry / resilience ---
engine.provider.retry.max-attempts=3              # LLM retry attempts
engine.provider.retry.delay1-seconds=10
engine.provider.retry.delay2-seconds=30
engine.step.timeout-seconds=30                    # per step
engine.loop.detection.threshold=5                # Loop detection
engine.preflight.enabled=true                    # Preflight checks (DNS/HTTP)
engine.preflight.ttl-seconds=60

# --- Observability (base) ---
quarkus.otel.enabled=false                       # OpenTelemetry off
quarkus.otel.service.name=qoc-engine
quarkus.log.level=DEBUG                          # File log DEBUG
quarkus.log.console.level=INFO                   # Console INFO
quarkus.log.file.enabled=true
quarkus.log.file.path=${LOG_DIR:./log}/qoc.log
quarkus.log.file.rotation.max-file-size=2M
quarkus.log.file.rotation.max-backup-index=10
engine.llm-log.enabled=true                      # LLM trace file
engine.llm-log.path=${LOG_DIR:./log}/llm.log
engine.llm-log.limit-bytes=2097152               # 2 MiB per file
engine.llm-log.count=20                          # 20 rotating files

# --- Workflow base ---
engine.workflow.recovery.enabled=true            # Resume incomplete runs
engine.workflow.max-steps=100                    # hard limit (0 = unlimited + monitoring)
engine.workflow.max-resume-attempts=10
```

---

## 1. Profile: DEV (allow-all, DB)

**Goal:** local development, fast iteration, full persistence,
minimal friction (no login, everything allowed).

### 1.1 Storage / DB

```properties
# --- Storage: Postgres (or SQLite for single-node) ---
engine.storage=postgres                           # postgres | sqlite | file | memory
engine.db.kind=postgresql                         # postgresql | sqlite
engine.db.path=${ENGINE_DB_PATH:./data/qoc.db}    # sqlite only
quarkus.datasource.db-kind=${engine.db.kind}
quarkus.datasource.jdbc.driver=${ENGINE_DB_DRIVER:}
quarkus.datasource.username=${ENGINE_DB_USER:qoc}
quarkus.datasource.password=${ENGINE_DB_PASSWORD:qoc}
quarkus.datasource.jdbc.url=${QUARKUS_DATASOURCE_JDBC_URL:jdbc:postgresql://localhost:5432/qoc}
quarkus.flyway.migrate-at-start=false            # migrations manual

# --- Workflow storage: follows engine (DB) ---
engine.workflow.storage=auto                      # auto | file | memory | postgres | sqlite  (auto follows engine.storage)
engine.workflow.storage-dir=./wf-data

# --- Retention (Dev: keep short) ---
engine.session.retention-days=${SESSION_RETENTION_DAYS:3}   # 3 days
engine.audit.retention-days=${ENGINE_AUDIT_RETENTION_DAYS:1} # 1 day
engine.revert.snapshot.enabled=true               # Revert snapshots ON (Dev debug)
```

### 1.2 Auth (no login)

```properties
# --- Auth: none (loopback) ---
engine.auth.password=${OPENCODE_SERVER_PASSWORD:}  # empty = no Basic Auth
# ENGINE_SECURITY_MODE not set → NONE
# OIDC off (default)
quarkus.oidc.enabled=false
```

### 1.3 Security / Permissions (allow-all)

```properties
# --- Permissions: Allow-All (OpenCode compatible) ---
engine.permission.default=allow-all               # least-privilege | allow-all
engine.permission.ask-mode=allow                  # allow | deny | interactive

# --- Shell sandbox: optional off in Dev ---
engine.shell.sandbox.enabled=false                # bwrap not needed in Dev
engine.shell.sandbox.mode=workspace-write

# --- Tool token: off (Dev) ---
engine.security.tool-token.enabled=false

# --- Email: on (Dev tests) ---
engine.email.enabled=true
engine.email.read-enabled=true

# --- Agent authoring / settings: on ---
engine.settings.workspace-enabled=true
# ENGINE_AGENT_AUTHORING_ENABLED not set → true

# --- A2A server: off ---
engine.a2a.server.enabled=false
```

### 1.4 Provider / Model (real LLM)

```properties
engine.provider.scripted=false                    # real LLM call
engine.provider.type=http
```

### 1.5 Observability (Dev: verbose)

```properties
quarkus.log.level=DEBUG
quarkus.log.console.level=DEBUG                   # everything to console
quarkus.log.file.enabled=true
engine.llm-log.enabled=true
quarkus.otel.enabled=false                       # no collector needed
```

### 1.6 Dev startup summary

```bash
# .env (Dev)
OPENAI_API_KEY=sk-...
ENGINE_PROVIDER_SCRIPTED=false
ENGINE_WORKSPACE=./workspace
ENGINE_DB_KIND=postgresql
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/qoc
SESSION_RETENTION_DAYS=3
ENGINE_AUDIT_RETENTION_DAYS=1
# ENGINE_SECURITY_MODE not set (NONE)
```

---

## 2. Profile: SECURE (least-privilege, In-Memory, Serverless)

**Goal:** hardened, ephemeral, resource-light operation (no DB,
no restart recovery, minimal attack surface, isolated shell).

### 2.1 Storage / In-Memory

```properties
# --- Storage: In-Memory (no DB, no network) ---
# Minimal mode: only Session/Conversation/Journal/Claim/Workspace/ToolOutput
# are retained; Snapshots, Approvals, Instructions, Credentials and
# Tool-Result-Cache are No-Ops.
engine.storage=memory                            # memory | postgres | sqlite | auto
# engine.db.kind is ignored
engine.recovery.enabled=false                    # no crash recovery

# --- Memory budgets (300 MiB total) ---
engine.memory.max-bytes=314572800                # 300 MiB
engine.memory.max-sessions=1000
engine.memory.max-tool-output-bytes=8388608      # 8 MiB
engine.memory.max-snapshot-bytes=67108864        # 64 MiB
engine.memory.max-messages-per-session=10000
engine.memory.journal-ring-events=50000
engine.memory.evict-finished=true

# --- Workflow storage: Memory (ephemeral) ---
engine.workflow.storage=memory
engine.workflow.recovery.enabled=false            # no resume
engine.workflow.max-steps=50                     # lower limit (or 0 + monitoring)
engine.workflow.max-resume-attempts=3

# --- Retention (Secure: short) ---
engine.session.retention-days=${SESSION_RETENTION_DAYS:1}   # 1 day
engine.audit.retention-days=${ENGINE_AUDIT_RETENTION_DAYS:1} # 1 day
engine.revert.snapshot.enabled=false              # no snapshot capture (performance + data volume)
```

### 2.2 Auth (OIDC or Basic)

```properties
# --- Option A: OIDC (Auth0/Keycloak) ---
quarkus.oidc.enabled=true
quarkus.oidc.auth-server-url=${QOC_OIDC_ISSUER:}
quarkus.oidc.client-id=${QOC_OIDC_CLIENT_ID:}
quarkus.oidc.credentials.secret=${QOC_OIDC_CLIENT_SECRET:}
quarkus.oidc.token.issuer=${QUARKUS_OIDC_TOKEN_ISSUER:${QOC_OIDC_ISSUER:}}
quarkus.oidc.token.audience=${QUARKUS_OIDC_TOKEN_AUDIENCE:qoc-engine}
ENGINE_SECURITY_MODE=oidc                        # or: settings.json → security.mode=oidc

# --- Option B: Basic Auth (Fallback) ---
# OPENCODE_SERVER_PASSWORD=<strong-password>     # lifts NONE to BASIC automatically
# ENGINE_SECURITY_MODE=basic
```

### 2.3 Security / Permissions (least-privilege)

```properties
# --- Permissions: Least-Privilege ---
engine.permission.default=least-privilege         # deny-by-default
engine.permission.ask-mode=deny                  # Headless: deny

# --- Shell sandbox: ON, workspace-write ---
engine.shell.sandbox.enabled=true                # bwrap (Native Host) or Mounts (Docker)
engine.shell.sandbox.mode=workspace-write
engine.shell.sandbox.env-allowlist=PATH,HOME,LANG,LC_ALL,LC_CTYPE,TMPDIR,TERM
engine.shell.sandbox.extraWritable=               # empty = workspace only
engine.shell.sandbox.uid=-1                      # auto
engine.shell.sandbox.gid=-1

# --- Tool token: ON (short-lived) ---
engine.security.tool-token.enabled=true
engine.security.tool-token.signing-key=${ENGINE_TOOL_TOKEN_SIGNING_KEY}  # Mandatory
engine.security.tool-token.ttl-seconds=60

# --- Email: OFF ---
engine.email.enabled=false
engine.email.read-enabled=false
engine.email.attachments-enabled=false

# --- Agent authoring / settings: OFF ---
engine.settings.workspace-enabled=false

# --- System user (service account) ---
ENGINE_SYSTEM_TOKEN=${ENGINE_SYSTEM_TOKEN:}        # Service token (empty = disabled)
ENGINE_SYSTEM_ROLES=${ENGINE_SYSTEM_ROLES:system}  # Roles (comma-separated)
ENGINE_SYSTEM_OWNERSHIP=${ENGINE_SYSTEM_OWNERSHIP:system}  # system | caller
# ENGINE_AGENT_AUTHORING_ENABLED=false

# --- A2A server: OFF (default) ---
engine.a2a.server.enabled=false

# --- Egress guard (A2A) ---
# engine.a2a.egress.allowedHosts=example.com,api.example.com
# engine.a2a.egress.allowedIps=10.0.0.0/8
# engine.a2a.egress.tlsRequired=true

# --- Trusted proxies (settings.json) ---
# security.trustedProxies = ["10.0.0.0/8"]       # in settings.json
```

### 2.4 Workspace / Serverless

```properties
# --- Serverless / Ephemeral ---
engine.workspace=${ENGINE_WORKSPACE:/data/workspace}
# mode=ephemeral → session-specific workspace under sessions/<session-id>
# In non-serverless mode, ephemeral falls back to the normal workspace

# --- User isolation ---
# Non-anonymous users → user-ws/<user> (path-traversal-guarded)
```

### 2.5 Observability (Secure: reduced)

```properties
quarkus.log.level=INFO                           # fewer logs
quarkus.log.console.level=INFO
quarkus.log.file.enabled=true
quarkus.log.file.level=INFO                      # no DEBUG in prod
engine.llm-log.enabled=false                      # no LLM trace (secrets)
quarkus.otel.enabled=false                       # or true with collector
engine.llm-trace.first-request-max=800           # reduced
engine.llm-trace.subsequent-request-max=400
engine.llm-trace.response-max=400
```

### 2.6 Container Hardening (Docker)

```yaml
# docker-compose (qoc-secure / qoc-secure-shell)
read_only: true
cap_drop: [ALL]
no-new-privileges: true
pids_limit: 256
user: "1000:1000"
tmpfs: ["/data", "/tmp"]
# Seccomp: deploy/seccomp-qoc-shell.json (ptrace, mount, unshare, bpf, ...)
# Mounts: only /data/workspace + /data/home (targeted)
```

### 2.7 Secure startup summary

```bash
# .env (Secure)
OPENAI_API_KEY=sk-...
ENGINE_SECURITY_MODE=oidc                        # or basic
QOC_OIDC_ISSUER=https://your-tenant.auth0.com/
QOC_OIDC_CLIENT_ID=...
QOC_OIDC_CLIENT_SECRET=...
ENGINE_STORAGE=memory
ENGINE_MEMORY_MAX_BYTES=314572800
ENGINE_PERMISSION_DEFAULT=least-privilege
ENGINE_PERMISSION_ASK_MODE=deny
ENGINE_SHELL_SANDBOX_ENABLED=true
ENGINE_TOOL_TOKEN_SIGNING_KEY=<32-byte-hex>
ENGINE_TOOL_TOKEN_ENABLED=true
ENGINE_EMAIL_ENABLED=false
ENGINE_AGENT_AUTHORING_ENABLED=false
ENGINE_SETTINGS_WORKSPACE_ENABLED=false
ENGINE_REVERT_SNAPSHOT=false
ENGINE_RECOVERY_ENABLED=false
ENGINE_WORKFLOW_STORAGE=memory
ENGINE_WORKFLOW_RECOVERY_ENABLED=false
SESSION_RETENTION_DAYS=1
ENGINE_AUDIT_RETENTION_DAYS=1
# OPENCODE_SERVER_PASSWORD only in basic mode
```

---

## 3. Quick Reference: Which line for what

| Goal | Key | Dev | Secure |
|---|---|---|---|
| Storage backend | `engine.storage` | `postgres` | `memory` |
| DB kind | `engine.db.kind` | `postgresql` | (ignored) |
| Recovery | `engine.recovery.enabled` | `true` | `false` |
| Memory budget | `engine.memory.max-bytes` | (n/a) | `314572800` |
| Permission default | `engine.permission.default` | `allow-all` | `least-privilege` |
| Ask mode | `engine.permission.ask-mode` | `allow` | `deny` |
| Shell sandbox | `engine.shell.sandbox.enabled` | `false` | `true` |
| Tool token | `engine.security.tool-token.enabled` | `false` | `true` |
| Agent authoring | `ENGINE_AGENT_AUTHORING_ENABLED` | (true) | `false` |
| Settings workspace | `engine.settings.workspace-enabled` | `true` | `false` |
| Revert snapshot | `engine.revert.snapshot.enabled` | `true` | `false` |
| Workflow storage | `engine.workflow.storage` | `auto` | `memory` |
| Workflow recovery | `engine.workflow.recovery.enabled` | `true` | `false` |
| Max steps | `engine.workflow.max-steps` | `100` | `50` |
| Session retention | `engine.session.retention-days` | `3` | `1` |
| Audit retention | `engine.audit.retention-days` | `1` | `1` |
| LLM log | `engine.llm-log.enabled` | `true` | `false` |
| Log level | `quarkus.log.level` | `DEBUG` | `INFO` |
| Auth | `ENGINE_SECURITY_MODE` | (unset/NONE) | `oidc`/`basic` |
| OIDC | `quarkus.oidc.enabled` | `false` | `true` |
| A2A server | `engine.a2a.server.enabled` | `false` | `false` |
| Email | `engine.email.enabled` | `true` | `false` |

---

## 4. Notes

1. **DB kind is build-time** (Quarkus): the image build must match the chosen backend
   (`build-image.sh` for Postgres, `build-image-sqlite.sh` for SQLite).
2. **Memory mode disables recovery** – no crash recovery, no restart.
   Suitable only for embedded/single-process.
3. **`engine.storage=memory` ignores `engine.db.kind`**.
4. **Workflow storage `auto` follows engine storage** – with a memory backend,
   workflows are ephemeral.
5. **Secrets** never in the image; via `.env` (mode 0600) or secrets manager.
6. **Engine listens only on 127.0.0.1** – public access only via nginx/TLS.
7. **Native Image**: workflow-runner is a library (no uber-JAR).
8. **Ephemeral mode** (`mode=ephemeral`): session-specific workspace under
   `sessions/<session-id>`; falls back to the normal workspace in non-serverless mode.
9. **User isolation**: non-anonymous users get `user-ws/<user>` beneath
   the base (path-traversal-guarded).
10. **Compaction** (`engine.compaction.*`) auto-compacts long histories.
11. **Temp dir / plan files**: `HOME` must be set (container without
    `/etc/passwd` → Java falls back to `/` → plan writes fail).
12. **Flyway**: `quarkus.flyway.migrate-at-start=false` – migrations manual
    or via Dev Services.
13. **Dev Services** for Postgres only in `%dev`/`%test`; disabled in prod.
14. **Max steps as safety net**: `0` = unlimited, but loop detection
    (`engine.loop.detection.threshold`) + token/cost budget + observability
    should serve as monitoring. Temporal (planned in workflow-runner)
    provides heartbeats/timeouts.
# CONFIG.md – Beispiel-Konfigurationen

> Kopierbare Konfigurations-Profile für QOC.
> Basis: `quarkus/src/main/resources/application.properties` + Umgebungsvariablen.
> Jede Zeile ist kommentiert. Abschnitte können einzeln in eine `.env` oder
> `application.properties` übernommen werden.
>
> **Geltung:** Werte ohne `${...}`-Default sind Pflichtangaben.
> Umgebungsvariablen überschreiben Properties (Quarkus-Standard).

---

## 0. Gemeinsame Basis (für beide Profile)

```properties
# --- HTTP ---
quarkus.http.port=8080                           # Listener-Port
quarkus.http.host=127.0.0.1                      # nur Loopback (nginx/TLS davor)
%test.quarkus.http.test-port=0                   # Tests: dynamischer Port

# --- Modell / Provider (LangChain4j) ---
quarkus.langchain4j.openai.api-key=${OPENAI_API_KEY:demo-key}   # LLM-Key aus ENV
quarkus.langchain4j.openai.chat-model.model-name=gpt-4o-mini     # Modell
quarkus.langchain4j.openai.chat-model.temperature=0.2           # Sampling

# --- Engine-Provider (HTTP-Adapter) ---
engine.provider.type=http                        # http | scripted
engine.provider.scripted=true                    # true = kein echter LLM-Call (Tests/Dev)
engine.provider.url=${OPENAI_BASE_URL:https://api.openai.com/v1}/chat/completions
engine.provider.api-key=${OPENAI_API_KEY:demo-key}
engine.model.provider=openai                     # Provider-ID
engine.model.id=${OPENAI_MODEL:gpt-4o-mini}      # Modell-ID

# --- Modell-Limits ---
engine.model.context-limit=512000                # Context-Fenster (Tokens)
engine.model.output-limit=128000                 # Max Output-Tokens

# --- Workspace ---
engine.workspace=${ENGINE_WORKSPACE:.}            # Basis-Verzeichnis
engine.session.temp-dir=${ENGINE_SESSION_TEMP_DIR:sessions}  # für ephemeral sessions
engine.settings.workspace-enabled=true            # Workspace-Settings erlauben

# --- Provider-Retry / Resilience ---
engine.provider.retry.max-attempts=3              # LLM-Retry-Versuche
engine.provider.retry.delay1-seconds=10
engine.provider.retry.delay2-seconds=30
engine.step.timeout-seconds=30                    # pro Step
engine.loop.detection.threshold=5                # Loop-Erkennung
engine.preflight.enabled=true                    # Preflight-Checks (DNS/HTTP)
engine.preflight.ttl-seconds=60

# --- Observability (Basis) ---
quarkus.otel.enabled=false                       # OpenTelemetry aus
quarkus.otel.service.name=qoc-engine
quarkus.log.level=DEBUG                          # Datei-Log DEBUG
quarkus.log.console.level=INFO                   # Konsole INFO
quarkus.log.file.enabled=true
quarkus.log.file.path=${LOG_DIR:./log}/qoc.log
quarkus.log.file.rotation.max-file-size=2M
quarkus.log.file.rotation.max-backup-index=10
engine.llm-log.enabled=true                      # LLM-Trace-Datei
engine.llm-log.path=${LOG_DIR:./log}/llm.log
engine.llm-log.limit-bytes=2097152               # 2 MiB pro Datei
engine.llm-log.count=20                          # 20 rotierende Dateien

# --- Workflow-Basis ---
engine.workflow.recovery.enabled=true            # Resume unvollständiger Runs
engine.workflow.max-steps=100                    # hartes Limit (0 = unbegrenzt+Monitoring)
engine.workflow.max-resume-attempts=10
```

---

## 1. Profil: DEV (allow-all, DB)

**Ziel:** lokale Entwicklung, schnelle Iteration, volle Persistenz,
minimale Reibung (kein Login, alles erlaubt).

### 1.1 Speicher / DB

```properties
# --- Storage: Postgres (oder SQLite für Single-Node) ---
engine.storage=postgres                           # postgres | sqlite | memory | auto
engine.db.kind=postgresql                         # postgresql | sqlite
engine.db.path=${ENGINE_DB_PATH:./data/qoc.db}    # nur bei sqlite
quarkus.datasource.db-kind=${engine.db.kind}
quarkus.datasource.jdbc.driver=${ENGINE_DB_DRIVER:}
quarkus.datasource.username=${ENGINE_DB_USER:qoc}
quarkus.datasource.password=${ENGINE_DB_PASSWORD:qoc}
quarkus.datasource.jdbc.url=${QUARKUS_DATASOURCE_JDBC_URL:jdbc:postgresql://localhost:5432/qoc}
quarkus.flyway.migrate-at-start=false            # Migrationen manuell

# --- Workflow-Storage: folgt Engine (DB) ---
engine.workflow.storage=auto                      # auto | file | memory | postgres | sqlite
engine.workflow.storage-dir=./wf-data

# --- Retention (Dev: kurz halten) ---
engine.session.retention-days=${SESSION_RETENTION_DAYS:3}   # 3 Tage
engine.audit.retention-days=${ENGINE_AUDIT_RETENTION_DAYS:1} # 1 Tag
engine.revert.snapshot.enabled=true               # Revert-Snapshots AN (Dev-Debug)
```

### 1.2 Auth (kein Login)

```properties
# --- Auth: none (loopback) ---
engine.auth.password=${OPENCODE_SERVER_PASSWORD:}  # leer = kein Basic-Auth
# ENGINE_SECURITY_MODE nicht gesetzt → NONE
# OIDC aus (Default)
quarkus.oidc.enabled=false
```

### 1.3 Security / Permissions (allow-all)

```properties
# --- Permissions: Allow-All (OpenCode-kompatibel) ---
engine.permission.default=allow-all               # least-privilege | allow-all
engine.permission.ask-mode=allow                  # allow | deny | interactive

# --- Shell-Sandbox: in Dev optional aus ---
engine.shell.sandbox.enabled=false                # bwrap in Dev nicht nötig
engine.shell.sandbox.mode=workspace-write

# --- Tool-Token: aus (Dev) ---
engine.security.tool-token.enabled=false

# --- Email: an (Dev-Tests) ---
engine.email.enabled=true
engine.email.read-enabled=true

# --- Agent-Authoring / Settings: an ---
engine.settings.workspace-enabled=true
# ENGINE_AGENT_AUTHORING_ENABLED nicht gesetzt → true

# --- A2A-Server: aus ---
engine.a2a.server.enabled=false
```

### 1.4 Provider / Modell (echter LLM)

```properties
engine.provider.scripted=false                    # echter LLM-Call
engine.provider.type=http
```

### 1.5 Observability (Dev: ausführlich)

```properties
quarkus.log.level=DEBUG
quarkus.log.console.level=DEBUG                   # alles auf Konsole
quarkus.log.file.enabled=true
engine.llm-log.enabled=true
quarkus.otel.enabled=false                       # kein Collector nötig
```

### 1.6 Zusammenfassung Dev-Start

```bash
# .env (Dev)
OPENAI_API_KEY=sk-...
ENGINE_PROVIDER_SCRIPTED=false
ENGINE_WORKSPACE=./workspace
ENGINE_DB_KIND=postgresql
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://localhost:5432/qoc
SESSION_RETENTION_DAYS=3
ENGINE_AUDIT_RETENTION_DAYS=1
# ENGINE_SECURITY_MODE nicht gesetzt (NONE)
```

---

## 2. Profil: SECURE (least-privilege, In-Memory, Serverless)

**Ziel:** gehärteter, flüchtiger, ressourcenarmer Betrieb (kein DB,
kein Restart-Recovery, minimale Angriffsfläche, Shell isoliert).

### 2.1 Speicher / In-Memory

```properties
# --- Storage: In-Memory (kein DB, kein Netzwerk) ---
engine.storage=memory                            # memory | postgres | sqlite | auto
# engine.db.kind wird ignoriert
engine.recovery.enabled=false                    # kein Crash-Recovery

# --- Memory-Budgets (300 MiB gesamt) ---
engine.memory.max-bytes=314572800                # 300 MiB
engine.memory.max-sessions=1000
engine.memory.max-tool-output-bytes=8388608      # 8 MiB
engine.memory.max-snapshot-bytes=67108864        # 64 MiB
engine.memory.max-messages-per-session=10000
engine.memory.journal-ring-events=50000
engine.memory.evict-finished=true

# --- Workflow-Storage: Memory (flüchtig) ---
engine.workflow.storage=memory
engine.workflow.recovery.enabled=false            # kein Resume
engine.workflow.max-steps=50                     # niedrigeres Limit (oder 0 + Monitoring)
engine.workflow.max-resume-attempts=3

# --- Retention (Secure: kurz) ---
engine.session.retention-days=${SESSION_RETENTION_DAYS:1}   # 1 Tag
engine.audit.retention-days=${ENGINE_AUDIT_RETENTION_DAYS:1} # 1 Tag
engine.revert.snapshot.enabled=false              # kein Snapshot-Capture (Performance + Datenmenge)
```

### 2.2 Auth (OIDC oder Basic)

```properties
# --- Option A: OIDC (Auth0/Keycloak) ---
quarkus.oidc.enabled=true
quarkus.oidc.auth-server-url=${QOC_OIDC_ISSUER:}
quarkus.oidc.client-id=${QOC_OIDC_CLIENT_ID:}
quarkus.oidc.credentials.secret=${QOC_OIDC_CLIENT_SECRET:}
quarkus.oidc.token.issuer=${QUARKUS_OIDC_TOKEN_ISSUER:${QOC_OIDC_ISSUER:}}
quarkus.oidc.token.audience=${QUARKUS_OIDC_TOKEN_AUDIENCE:qoc-engine}
ENGINE_SECURITY_MODE=oidc                        # oder: settings.json → security.mode=oidc

# --- Option B: Basic Auth (Fallback) ---
# OPENCODE_SERVER_PASSWORD=<starkes-passwort>     # hebt NONE automatisch auf BASIC
# ENGINE_SECURITY_MODE=basic
```

### 2.3 Security / Permissions (least-privilege)

```properties
# --- Permissions: Least-Privilege ---
engine.permission.default=least-privilege         # deny-by-default
engine.permission.ask-mode=deny                  # Headless: deny

# --- Shell-Sandbox: AN, workspace-write ---
engine.shell.sandbox.enabled=true                # bwrap (Native Host) oder Mounts (Docker)
engine.shell.sandbox.mode=workspace-write
engine.shell.sandbox.env-allowlist=PATH,HOME,LANG,LC_ALL,LC_CTYPE,TMPDIR,TERM
engine.shell.sandbox.extraWritable=               # leer = nur Workspace
engine.shell.sandbox.uid=-1                      # auto
engine.shell.sandbox.gid=-1

# --- Tool-Token: AN (kurzlebig) ---
engine.security.tool-token.enabled=true
engine.security.tool-token.signing-key=${ENGINE_TOOL_TOKEN_SIGNING_KEY}  # Pflicht
engine.security.tool-token.ttl-seconds=60

# --- Email: AUS ---
engine.email.enabled=false
engine.email.read-enabled=false
engine.email.attachments-enabled=false

# --- Agent-Authoring / Settings: AUS ---
engine.settings.workspace-enabled=false
# ENGINE_AGENT_AUTHORING_ENABLED=false

# --- A2A-Server: AUS (Default) ---
engine.a2a.server.enabled=false

# --- Egress Guard (A2A) ---
# engine.a2a.egress.allowedHosts=example.com,api.example.com
# engine.a2a.egress.allowedIps=10.0.0.0/8
# engine.a2a.egress.tlsRequired=true

# --- Trusted Proxies (settings.json) ---
# security.trustedProxies = ["10.0.0.0/8"]       # in settings.json
```

### 2.4 Workspace / Serverless

```properties
# --- Serverless / Ephemeral ---
engine.workspace=${ENGINE_WORKSPACE:/data/workspace}
# mode=ephemeral → session-spezifischer Workspace unter sessions/<session-id>
# In Non-Serverless-Mode fällt ephemeral auf normalen Workspace zurück

# --- User-Isolation ---
# Non-anonymous Users → user-ws/<user> (Path-Traversal-guarded)
```

### 2.5 Observability (Secure: reduziert)

```properties
quarkus.log.level=INFO                           # weniger Logs
quarkus.log.console.level=INFO
quarkus.log.file.enabled=true
quarkus.log.file.level=INFO                      # kein DEBUG in Prod
engine.llm-log.enabled=false                      # kein LLM-Trace (Secrets)
quarkus.otel.enabled=false                       # oder true mit Collector
engine.llm-trace.first-request-max=800           # reduziert
engine.llm-trace.subsequent-request-max=400
engine.llm-trace.response-max=400
```

### 2.6 Container-Hardening (Docker)

```yaml
# docker-compose (qoc-secure / qoc-secure-shell)
read_only: true
cap_drop: [ALL]
no-new-privileges: true
pids_limit: 256
user: "1000:1000"
tmpfs: ["/data", "/tmp"]
# Seccomp: deploy/seccomp-qoc-shell.json (ptrace, mount, unshare, bpf, ...)
# Mounts: nur /data/workspace + /data/home (targeted)
```

### 2.7 Zusammenfassung Secure-Start

```bash
# .env (Secure)
OPENAI_API_KEY=sk-...
ENGINE_SECURITY_MODE=oidc                        # oder basic
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
# OPENCODE_SERVER_PASSWORD nur bei basic-Modus
```

---

## 3. Schnellreferenz: Welche Zeile wofür

| Ziel | Key | Dev | Secure |
|---|---|---|---|
| Storage-Backend | `engine.storage` | `postgres` | `memory` |
| DB-Kind | `engine.db.kind` | `postgresql` | (ignoriert) |
| Recovery | `engine.recovery.enabled` | `true` | `false` |
| Memory-Budget | `engine.memory.max-bytes` | (n/a) | `314572800` |
| Permission-Default | `engine.permission.default` | `allow-all` | `least-privilege` |
| Ask-Mode | `engine.permission.ask-mode` | `allow` | `deny` |
| Shell-Sandbox | `engine.shell.sandbox.enabled` | `false` | `true` |
| Tool-Token | `engine.security.tool-token.enabled` | `false` | `true` |
| Agent-Authoring | `ENGINE_AGENT_AUTHORING_ENABLED` | (true) | `false` |
| Settings-Workspace | `engine.settings.workspace-enabled` | `true` | `false` |
| Revert-Snapshot | `engine.revert.snapshot.enabled` | `true` | `false` |
| Workflow-Storage | `engine.workflow.storage` | `auto` | `memory` |
| Workflow-Recovery | `engine.workflow.recovery.enabled` | `true` | `false` |
| Max-Steps | `engine.workflow.max-steps` | `100` | `50` |
| Session-Retention | `engine.session.retention-days` | `3` | `1` |
| Audit-Retention | `engine.audit.retention-days` | `1` | `1` |
| LLM-Log | `engine.llm-log.enabled` | `true` | `false` |
| Log-Level | `quarkus.log.level` | `DEBUG` | `INFO` |
| Auth | `ENGINE_SECURITY_MODE` | (unset/NONE) | `oidc`/`basic` |
| OIDC | `quarkus.oidc.enabled` | `false` | `true` |
| A2A-Server | `engine.a2a.server.enabled` | `false` | `false` |
| Email | `engine.email.enabled` | `true` | `false` |

---

## 4. Hinweise

1. **DB-Kind ist build-time** (Quarkus): Image-Build muss zum gewählten Backend passen
   (`build-image.sh` für Postgres, `build-image-sqlite.sh` für SQLite).
2. **Memory-Modus deaktiviert Recovery** – kein Crash-Recovery, kein Restart.
   Nur für embedded/single-process geeignet.
3. **`engine.storage=memory` ignoriert `engine.db.kind`**.
4. **Workflow-Storage `auto` folgt Engine-Storage** – bei Memory-Backend
   sind Workflows flüchtig.
5. **Secrets** nie im Image; über `.env` (mode 0600) oder Secrets Manager.
6. **Engine lauscht nur auf 127.0.0.1** – öffentlicher Zugang nur über nginx/TLS.
7. **Native Image**: workflow-runner ist Library (kein Uber-JAR).
8. **Ephemeral Mode** (`mode=ephemeral`): session-spezifischer Workspace unter
   `sessions/<session-id>`; fällt in Non-Serverless-Mode auf normalen Workspace zurück.
9. **User-Isolation**: Non-anonymous Users bekommen `user-ws/<user>` unterhalb
   der Base (Path-Traversal-guarded).
10. **Compaction** (`engine.compaction.*`) auto-kompaktiert lange Histories.
11. **Temp-Dir / Plan-Files**: `HOME` muss gesetzt sein (Container ohne
    `/etc/passwd` → Java fällt auf `/` zurück → Plan-Writes schlagen fehl).
12. **Flyway**: `quarkus.flyway.migrate-at-start=false` – Migrationen manuell
    oder über Dev-Services.
13. **Dev-Services** für Postgres nur in `%dev`/`%test`; prod deaktiviert.
14. **Max-Steps als Safety Net**: `0` = unbegrenzt, aber Loop-Detection
    (`engine.loop.detection.threshold`) + Token/Cost-Budget + Observability
    sollten als Überwachung dienen. Temporal (im workflow-runner geplant)
    liefert Heartbeats/Timeouts.
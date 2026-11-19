# settings.md — Configuration hierarchy and modes

## 1. Overview

QOC has three independent configuration layers:

```
┌─────────────────────────────────────────────────────────────┐
│  Layer 1: Infrastructure (.env / application.properties)     │
│  DB, Provider, Ports, Logging, Retention, Resilience         │
├─────────────────────────────────────────────────────────────┤
│  Layer 2: Settings (settings.json / settings.jsonc)         │
│  Agents, Skills, Permissions, MCP, Guardrails               │
├─────────────────────────────────────────────────────────────┤
│  Layer 3: Agent Frontmatter (.opencode/agents/*.md)         │
│  Permissions, Tools, Mode, MaxSteps, RolesAllowed           │
└─────────────────────────────────────────────────────────────┘
```

## 2. Infrastructure Config (.env / application.properties)

### Modes

| Mode | `ENGINE_PERMISSION_DEFAULT` | `ENGINE_PERMISSION_ASK_MODE` | Usage |
|---|---|---|---|
| **Dev** | `allow-all` | `interactive` | Local development, hot-reload, UI |
| **Secure** | `least-privilege` | `deny` | Production, headless, remote |
| **CI/Test** | `allow-all` | `allow` | Automated tests |

### Relevant Properties

```properties
# Permission mode
ENGINE_PERMISSION_DEFAULT=least-privilege|allow-all
ENGINE_PERMISSION_ASK_MODE=deny|allow|interactive

# Resilience
ENGINE_PREFLIGHT_ENABLED=true
# DNS/HTTP results are cached per provider URL for this TTL
ENGINE_PREFLIGHT_TTL_SECONDS=60
ENGINE_PREFLIGHT_TIMEOUT_SECONDS=5
ENGINE_PREFLIGHT_DNS_ENABLED=true
ENGINE_PREFLIGHT_HTTP_CHECK_ENABLED=true
ENGINE_STEP_TIMEOUT_SECONDS=30
ENGINE_LOOP_DETECTION_THRESHOLD=5
ENGINE_RETRY_MAX_ATTEMPTS=3
ENGINE_RETRY_BASE_DELAY_MS=1000
ENGINE_RETRY_MAX_DELAY_MS=60000
ENGINE_RETRY_JITTER_MS=5000
ENGINE_TRIAGE_ENABLED=false
ENGINE_TRIAGE_MAX_ATTEMPTS=3
ENGINE_WEBHOOK_ENABLED=false
ENGINE_WEBHOOK_URL=
ENGINE_WEBHOOK_FORMAT=generic

# Logging
ENGINE_LOG_JSON=true
ENGINE_AUDIT_RETENTION_DAYS=30
ENGINE_LLM_LOG_ENABLED=true

# Workspace settings (security)
ENGINE_SETTINGS_WORKSPACE_ENABLED=true|false
```

## 3. Settings Files (settings.json / settings.jsonc)

### Merge Order

```
1. Global settings (engine.settings.global → ~/.config/opencode/settings.json)
   ↓ merge (non-security keys)
2. Project settings (workspace/settings.jsonc, if enabled)
   ↓ merge (field-by-field)
3. Agent frontmatter (.opencode/agents/*.md)
   ↓ last-match-wins
4. Session info (permissions from DB)
```

### Security Rule

Agents and permissions come from **both** files by default. With
`ENGINE_SETTINGS_WORKSPACE_ENABLED=false` they come **only** from the global
`settings.json` — preventing a workspace owner from defining arbitrary agents
or permissions.

### settings.jsonc Structure

```jsonc
{
  "skills": {
    "paths": ["./skills"]           // Skill libraries
  },
  "permissions": [
    { "action": "skill", "resource": "*", "effect": "deny" },
    { "action": "skill", "resource": "aisdlc-*", "effect": "allow" },
    { "action": "subagent", "resource": "aisdlc-*", "effect": "allow" },
    { "action": "shell", "resource": "*mvn*", "effect": "allow" }
  ]
}
```

## 4. Agent Frontmatter (.opencode/agents/*.md)

```yaml
---
description: "Agent description"
mode: all | primary | subagent
steps: 150
permissions:
  - action: "*"
    resource: "*"
    effect: deny
  - action: read
    resource: "*"
    effect: allow
  - action: edit
    resource: "*"
    effect: allow
  - action: shell
    resource: "*mvn*"
    effect: allow
---
```

### Permission Rules (last-match-wins)

| Rule | Effect | Description |
|---|---|---|
| `*:* = deny` | DENY | Baseline: everything forbidden |
| `read * = allow` | ALLOW | Reading allowed |
| `external_directory * = deny` | DENY | External locked out |
| `edit/write/patch * = deny` | DENY | No changes (default) |
| `shell * = ask` | ASK | Unknown shell commands prompt |
| `shell *ls* = allow` | ALLOW | Read-only commands |
| `read *.env = ask` | ASK | Secrets prompt |

## 5. Override Order (low → high)

```
1. Global baseline (ContextBuilder.leastPrivilegeDefaults / allowAllDefaults)
   ↓ overridden by
2. Agent-specific rules (frontmatter permissions)
   ↓ overridden by
3. Skill rules (per-agent skill allow/deny)
   ↓ overridden by
4. Session rules (from SessionInfo.permissions() in DB)
   ↓ evaluated by
5. PermissionEngine.decide() → PermissionRules (last-match-wins)
   ↓ on "ask":
6. resolveAskMode() → Agent override (DENY for subagents) or global AskMode
   ↓
7. assertAllowed() → PermissionDenied or PausedException (HITL)
```

## 6. Ask Modes in Detail

| Mode | Behavior for `ask` rules | Use Case |
|---|---|---|
| `deny` | Auto-deny → `PermissionDenied("auto-denied")` | Headless, REST, MCP, CI |
| `allow` | Auto-allow → tool is executed | Tests, permissive |
| `interactive` | Blocks, waits for human approval (UI/API) | Development |

**Agent-Level Override:** Subagents always get `AskMode.DENY` (regardless of the
global setting). Primary agents inherit the global mode.

## 7. Example 1: Dev Mode (almost everything allowed)

**Goal:** Local development with hot-reload, UI, interactive approvals, maximum access.

### .env (dev)

```dotenv
# Provider
OPENAI_API_KEY=sk-...
ENGINE_PROVIDER_URL=https://opencode.ai/zen/go/v1/chat/completions
ENGINE_MODEL_ID=deepseek-v4.1-flash
ENGINE_PROVIDER_SCRIPTED=false

# Database (host Postgres)
ENGINE_DB_KIND=postgresql
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://host.docker.internal:20000/qoc?sslmode=disable
QUARKUS_DATASOURCE_USERNAME=postgres
QUARKUS_DATASOURCE_PASSWORD=postgres

# Permissions: permissive
ENGINE_PERMISSION_DEFAULT=allow-all
ENGINE_PERMISSION_ASK_MODE=interactive

# Logging: verbose
QUARKUS_LOG_LEVEL=DEBUG
QUARKUS_LOG_FILE_LEVEL=DEBUG
ENGINE_LLM_LOG_ENABLED=true

# Shell: sandbox disabled (Docker cannot use namespaces)
ENGINE_SHELL_SANDBOX_ENABLED=false

# Workspace settings: enabled (agents from .opencode/agents/)
ENGINE_SETTINGS_WORKSPACE_ENABLED=true
ENGINE_WORKSPACE=/data/workspace

# Retention: short (dev)
SESSION_RETENTION_DAYS=7
ENGINE_AUDIT_RETENTION_DAYS=2

# Resilience: off (dev)
ENGINE_PREFLIGHT_ENABLED=false
ENGINE_TRIAGE_ENABLED=false
ENGINE_WEBHOOK_ENABLED=false
```

### settings.jsonc (dev)

```jsonc
{
  "skills": {
    "paths": ["./skills", "../skills/aisdlc"]
  },
  "permissions": [
    { "action": "skill", "resource": "*", "effect": "allow" },
    { "action": "subagent", "resource": "*", "effect": "allow" },
    { "action": "shell", "resource": "*", "effect": "allow" },
    { "action": "edit", "resource": "*", "effect": "allow" },
    { "action": "write", "resource": "*", "effect": "allow" }
  ]
}
```

### Agent Frontmatter (dev, e.g. build agent)

```yaml
---
description: "Build agent with full access for development"
mode: all
steps: 200
permissions:
  - action: "*"
    resource: "*"
    effect: allow
---
```

### Properties
- **Everything allowed:** `*:* = allow` (no deny baseline)
- **Interactive approvals:** `ask` blocks for human approval (UI/API)
- **Shell sandbox off:** bwrap cannot use namespaces in Docker
- **Workspace settings active:** agents from `.opencode/agents/` are loaded
- **Verbose logging:** DEBUG level, LLM trace active
- **Resilience off:** no preflight checks, no triage, no webhook

---

## 8. Example 2: Secure Mode (restricted, headless/remote)

**Goal:** Automated operation without human input, least-privilege, hardened.

### .env (secure)

```dotenv
# Provider
OPENAI_API_KEY=sk-...
ENGINE_PROVIDER_URL=https://opencode.ai/zen/go/v1/chat/completions
ENGINE_MODEL_ID=deepseek-v4.1-flash
ENGINE_PROVIDER_SCRIPTED=false

# Database (Postgres)
ENGINE_DB_KIND=postgresql
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://host.docker.internal:20000/qoc?sslmode=disable
QUARKUS_DATASOURCE_USERNAME=postgres
QUARKUS_DATASOURCE_PASSWORD=postgres

# Permissions: restricted
ENGINE_PERMISSION_DEFAULT=least-privilege
ENGINE_PERMISSION_ASK_MODE=deny

# Logging: structured, JSON
QUARKUS_LOG_LEVEL=INFO
QUARKUS_LOG_CONSOLE_JSON=true
QUARKUS_LOG_FILE_JSON=true
ENGINE_LLM_LOG_ENABLED=true

# Shell: sandbox disabled (Docker cannot use namespaces)
ENGINE_SHELL_SANDBOX_ENABLED=false

# Workspace settings: disabled (only global settings.json)
ENGINE_SETTINGS_WORKSPACE_ENABLED=false
ENGINE_WORKSPACE=/data/workspace

# Retention: long (production)
# Note: Code defaults are SESSION_RETENTION_DAYS=7 and
# ENGINE_AUDIT_RETENTION_DAYS=2; the following values are a Secure example.
SESSION_RETENTION_DAYS=14
ENGINE_AUDIT_RETENTION_DAYS=30

# Resilience: active
ENGINE_PREFLIGHT_ENABLED=true
ENGINE_STEP_TIMEOUT_SECONDS=30
ENGINE_LOOP_DETECTION_THRESHOLD=5
ENGINE_RETRY_MAX_ATTEMPTS=3
ENGINE_RETRY_BASE_DELAY_MS=1000
ENGINE_RETRY_MAX_DELAY_MS=60000
ENGINE_RETRY_JITTER_MS=5000
ENGINE_TRIAGE_ENABLED=false
ENGINE_WEBHOOK_ENABLED=true
ENGINE_WEBHOOK_URL=https://hooks.slack.com/services/...
ENGINE_WEBHOOK_FORMAT=slack
```

### settings.jsonc (secure, project-level)

```jsonc
{
  "skills": {
    "paths": []
  },
  "permissions": [
    { "action": "skill", "resource": "*", "effect": "deny" },
    { "action": "skill", "resource": "aisdlc-*", "effect": "allow" },
    { "action": "subagent", "resource": "aisdlc-*", "effect": "allow" }
  ]
}
```

### Agent Frontmatter (secure, e.g. build agent)

```yaml
---
description: "Build agent with least-privilege for production"
mode: all
steps: 50
permissions:
  - action: "*"
    resource: "*"
    effect: deny
  - action: read
    resource: "*"
    effect: allow
  - action: edit
    resource: "src/**"
    effect: allow
  - action: write
    resource: "src/**"
    effect: allow
  - action: shell
    resource: "*mvn*"
    effect: allow
  - action: shell
    resource: "*git log*"
    effect: allow
  - action: shell
    resource: "*git status*"
    effect: allow
  - action: shell
    resource: "*git diff*"
    effect: allow
  - action: shell
    resource: "*ls*"
    effect: allow
  - action: shell
    resource: "*cat*"
    effect: allow
---
```

### Properties
- **Least-privilege:** `*:* = deny` baseline, only explicitly allowed actions
- **No human input:** `ask` → auto-deny, no HITL blocking
- **Workspace settings disabled:** only global `settings.json` defines agents/permissions
- **JSON logging:** structured logs for log aggregation (ELK, Grafana Loki)
- **Resilience active:** preflight checks, timeouts, loop detection, webhook alerting
- **Long retention:** 14 days sessions, 30 days audit
- **Read-only filesystem:** container runs as non-root, read-only root, tmpfs for /data and /tmp

## 9. settings.json vs. settings.jsonc

| | `settings.json` (global) | `settings.jsonc` (project) |
|---|---|---|
| **Location** | `~/.config/opencode/settings.json` | `<workspace>/settings.jsonc` |
| **Scope** | User-level (all projects) | Project-level (only this project) |
| **Defines** | Agents, Permissions, Skills, MCP, Guardrails | Skills (paths), Permissions (skill/subagent allow) |
| **Merge** | Loaded first | Overrides non-security keys |
| **Security** | Always active | Can be disabled (`ENGINE_SETTINGS_WORKSPACE_ENABLED=false`) |
| **Format** | JSON | JSONC (with comments) |

## 10. Agent Frontmatter: Mode and Permissions

### Mode (`mode`)
| Value | Meaning |
|---|---|
| `all` | Primary (directly selectable) AND subagent (startable by orchestrator) |
| `primary` | Only directly selectable, **not** startable as subagent |
| `subagent` | Only startable as subagent, not directly selectable |

### Permission Override (last-match-wins)
Agent-specific rules are appended **after** the baseline and override it:
```yaml
permissions:
  - action: "*"          # Baseline: deny all
    resource: "*"
    effect: deny
  - action: read         # Override: read allow
    resource: "*"
    effect: allow
  - action: edit         # Override: edit deny (even more restrictive)
    resource: "*"
    effect: deny
  - action: edit         # Override: edit allow for src/
    resource: "src/**"
    effect: allow
```

## 11. Workspace Scoping in OIDC Mode (User vs. Admin)

### 11.1 Overview

In OIDC mode (`ENGINE_SECURITY_MODE=oidc`) workspace access is restricted
role-based. The workspace path is determined per session based on the
principal's roles:

| Role | Workspace path | Access |
|---|---|---|
| **Admin** (`admin`, `qoc-admin`) | `/data/workspace` (global) | Full access to all projects and workspaces |
| **User** (`user`, `qoc-user`) | `/data/workspace/user-ws/{userId}` | Only own workspace area |

### 11.1a Distinction: User ID vs. User Role

QOC distinguishes two concepts:

| Concept | Field | Usage | Example |
|---|---|---|---|
| **User ID** | `Principal.id` | Workspace path (`user-ws/{userId}`), session assignment | `alice`, `bob`, `admin1` |
| **User Role** | `Principal.roles` | Agent visibility (`rolesAllowed`), filesystem access level (admin → global, user → isolated) | `admin`, `user`, `qoc-admin` |

- The **User ID** determines the individual workspace (`user-ws/{userId}`).
- The **User Role** determines whether the user may see the global workspace (Admin) or only their own (User).
- An admin user has their own User ID (e.g. `admin1`) and still gets `user-ws/admin1` as the session workspace, but can access the global workspace through the filesystem layer (`allowedBaseFor`).
- Roles are stored in `sessionRoles` at session creation and passed on to subagents (for authorization).

### 11.2 How It Works

1. **Authentication:** The user signs in via OIDC (Keycloak/Auth0).
   The token contains the role (`user` or `admin`).

2. **Workspace resolution:** At session creation (`POST /api/session`) the
   workspace path is determined based on the principal's roles:
   - Admin → `/data/workspace` (global workspace)
   - User → `/data/workspace/user-ws/{userId}` (isolated per user)

3. **Filesystem boundary:** The `FileAccess` layer checks on every
   `read`/`write`/`edit`/`patch`/`glob`/`grep` call whether the path lies
   within the assigned workspace. Access outside is rejected
   (`external_directory` permission).

4. **Shell sandbox:** The shell scanner checks on every shell command whether
   the referenced directories lie within the workspace. Commands that access
   outside are blocked.

### 11.3 Configuration

```properties
# Enable OIDC
ENGINE_SECURITY_MODE=oidc
QUARKUS_OIDC_ENABLED=true
QUARKUS_OIDC_AUTH_SERVER_URL=http://keycloak:8080/realms/qoc
QUARKUS_OIDC_TOKEN_ISSUER=http://localhost:8180/realms/qoc
QUARKUS_OIDC_TOKEN_AUDIENCE=qoc-engine

# Workspace scoping
ENGINE_WORKSPACE=/data/workspace              # Base workspace (Admin)
ENGINE_USER_WORKSPACE_TEMPLATE=/data/workspace/user-ws/{userId}  # User workspace
```

### 11.4 Behavior in Detail

| Action | Admin | User |
|---|---|---|
| `read /data/workspace/project-a/...` | ✅ allowed | ❌ denied (outside own workspace) |
| `read /data/workspace/user-ws/alice/...` | ✅ allowed (global access) | ✅ only for alice |
| `write /data/workspace/project-a/...` | ✅ allowed | ❌ denied |
| `shell "ls /data/workspace"` | ✅ allowed | ❌ denied (external_directory) |
| `shell "ls /data/workspace/user-ws/alice"` | ✅ allowed | ✅ allowed |
| Agent selection | All agents | Only allowed agents (rolesAllowed) |
| Session access | All sessions | Only own sessions |

### 11.5 Security Guarantees

1. **Filesystem isolation:** Users cannot access other users' workspaces or the
   global workspace. The `FileAccess` layer checks every path against the
   assigned workspace root.

2. **Shell isolation:** The shell scanner extracts all path references from
   shell commands and checks them against the workspace root. Commands that
   access outside are blocked (`external_directory` permission).

3. **Agent isolation:** Users only see agents they are authorized for
   (`rolesAllowed`). Admin agents are not visible to users.

4. **Session isolation:** Users can only access their own sessions.
   Admins can access all sessions.

5. **Permission isolation:** Each session has its own permission rules. User
   sessions cannot inherit the permissions of admin sessions.

### 11.6 Development vs. Production

| Aspect | Development (local Keycloak) | Production (Auth0) |
|---|---|---|
| IdP | Keycloak (port 8180) | Auth0 |
| Test user | `user`/`user`, role `user` | Real tenant user |
| Admin user | `admin`/`admin`, role `admin` | Real admin account |
| Workspace scoping | Active (User → `/data/workspace/user-ws/{userId}`) | Active |
| OIDC overlay | `docker-compose.oidc.yml` | Native OIDC config |

### 11.7 Known Limitations

- **No multi-tenancy:** All users share one QOC instance. Workspace
  isolation happens at the filesystem level, not the database level.
- **No quota management:** Users can create unlimited sessions/workspaces.
- **No per-user audit trail:** Audit logs are global, not filtered per user.
- **Admin access:** Admins have full access to all workspaces. There is no
  fine-grained admin permission (e.g. "read only, no write").

---

## 12. Relevant Files

| File | Purpose |
|---|---|
| `.env` / `.env.example` | Infrastructure config (secrets, DB, provider, ports, resilience) |
| `application.properties` | Quarkus config defaults and config keys |
| `~/.config/opencode/settings.json` | Global settings (agents, permissions, skills, MCP) |
| `<workspace>/settings.jsonc` | Project settings (skills, subagent allow) |
| `<workspace>/.opencode/agents/*.md` | Agent definitions (frontmatter + system prompt) |
| `<workspace>/.opencode/skills/` | Skill libraries |
| `EngineConfig.java` | Config parsing, baseline agents, settings merge |
| `ContextBuilder.java` | leastPrivilegeDefaults(), allowAllDefaults(), defaults() |
| `PermissionEngine.java` | decide(), assertAllowed(), resolveAskMode() |
| `PermissionRules.java` | Rule evaluation (last-match-wins) |
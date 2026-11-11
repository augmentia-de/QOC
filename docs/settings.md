# settings.md — Konfigurationshierarchie und Modi

## 1. Übersicht

QOC hat drei unabhängige Konfigurationsebenen:

```
┌─────────────────────────────────────────────────────────────┐
│  Ebene 1: Infrastruktur (.env / application.properties)     │
│  DB, Provider, Ports, Logging, Retention, Resilience         │
├─────────────────────────────────────────────────────────────┤
│  Ebene 2: Settings (settings.json / settings.jsonc)         │
│  Agents, Skills, Permissions, MCP, Guardrails               │
├─────────────────────────────────────────────────────────────┤
│  Ebene 3: Agent-Frontmatter (.opencode/agents/*.md)         │
│  Permissions, Tools, Mode, MaxSteps, RolesAllowed           │
└─────────────────────────────────────────────────────────────┘
```

## 2. Infrastruktur-Config (.env / application.properties)

### Modi

| Modus | `ENGINE_PERMISSION_DEFAULT` | `ENGINE_PERMISSION_ASK_MODE` | Verwendung |
|---|---|---|---|
| **Dev** | `allow-all` | `interactive` | Lokale Entwicklung, Hot-Reload, UI |
| **Secure** | `least-privilege` | `deny` | Produktion, Headless, Remote |
| **CI/Test** | `allow-all` | `allow` | Automatisierte Tests |

### Relevante Properties

```properties
# Permission-Modus
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

# Workspace-Settings (Sicherheit)
ENGINE_SETTINGS_WORKSPACE_ENABLED=true|false
```

## 3. Settings-Dateien (settings.json / settings.jsonc)

### Merge-Reihenfolge

```
1. Global settings (engine.settings.global → ~/.config/opencode/settings.json)
   ↓ merge (non-security keys)
2. Project settings (workspace/settings.jsonc, wenn enabled)
   ↓ merge (field-by-field)
3. Agent-Frontmatter (.opencode/agents/*.md)
   ↓ last-match-wins
4. Session-Info (permissions aus DB)
```

### Sicherheitsregel
Agents und Permissions kommen standardmäßig aus **beiden** Dateien. Mit
`ENGINE_SETTINGS_WORKSPACE_ENABLED=false` kommen sie **nur** aus der globalen
`settings.json` — verhindert, dass ein Workspace-Owner beliebige Agenten
oder Rechte definiert.

### settings.jsonc Struktur

```jsonc
{
  "skills": {
    "paths": ["./skills"]           // Skill-Bibliotheken
  },
  "permissions": [
    { "action": "skill", "resource": "*", "effect": "deny" },
    { "action": "skill", "resource": "aisdlc-*", "effect": "allow" },
    { "action": "subagent", "resource": "aisdlc-*", "effect": "allow" },
    { "action": "shell", "resource": "*mvn*", "effect": "allow" }
  ]
}
```

## 4. Agent-Frontmatter (.opencode/agents/*.md)

```yaml
---
description: "Agent-Beschreibung"
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

### Permission-Regeln (last-match-wins)

| Regel | Effekt | Beschreibung |
|---|---|---|
| `*:* = deny` | DENY | Baseline: alles verboten |
| `read * = allow` | ALLOW | Lesen erlaubt |
| `external_directory * = deny` | DENY | Extern gesperrt |
| `edit/write/patch * = deny` | DENY | Nichts ändern (Default) |
| `shell * = ask` | ASK | Unbekannte Shell-Befehle fragen nach |
| `shell *ls* = allow` | ALLOW | Read-only Befehle |
| `read *.env = ask` | ASK | Secrets nachfragen |

## 5. Überschreibungsreihenfolge (niedrig → hoch)

```
1. Globale Baseline (ContextBuilder.leastPrivilegeDefaults / allowAllDefaults)
   ↓ überschrieben von
2. Agent-spezifische Regeln (Frontmatter permissions)
   ↓ überschrieben von
3. Skill-Rules (per-Agent skill allow/deny)
   ↓ überschrieben von
4. Session-Rules (aus SessionInfo.permissions() aus DB)
   ↓ evaluiert durch
5. PermissionEngine.decide() → PermissionRules (last-match-wins)
   ↓ bei "ask":
6. resolveAskMode() → Agent-Override (DENY für Subagents) oder globaler AskMode
   ↓
7. assertAllowed() → PermissionDenied oder PausedException (HITL)
```

## 6. Ask-Modi im Detail

| Modus | Verhalten bei `ask`-Regeln | Use Case |
|---|---|---|
| `deny` | Auto-Deny → `PermissionDenied("auto-denied")` | Headless, REST, MCP, CI |
| `allow` | Auto-Allow → Tool wird ausgeführt | Tests, permissiv |
| `interactive` | Blockiert, wartet auf Human-Approval (UI/API) | Entwicklung |

**Agent-Level Override:** Subagents bekommen immer `AskMode.DENY` (unabhängig vom globalen Setting). Primäre Agenten erben den globalen Modus.

## 7. Beispiel 1: Dev-Modus (fast alles erlaubt)

**Ziel:** Lokale Entwicklung mit Hot-Reload, UI, interaktive Freigaben, maximaler Zugriff.

### .env (dev)

```dotenv
# Provider
OPENAI_API_KEY=sk-...
ENGINE_PROVIDER_URL=https://opencode.ai/zen/go/v1/chat/completions
ENGINE_MODEL_ID=deepseek-v4.1-flash
ENGINE_PROVIDER_SCRIPTED=false

# Datenbank (Host-Postgres)
ENGINE_DB_KIND=postgresql
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://host.docker.internal:20000/qoc?sslmode=disable
QUARKUS_DATASOURCE_USERNAME=postgres
QUARKUS_DATASOURCE_PASSWORD=postgres

# Permissions: permissiv
ENGINE_PERMISSION_DEFAULT=allow-all
ENGINE_PERMISSION_ASK_MODE=interactive

# Logging: verbose
QUARKUS_LOG_LEVEL=DEBUG
QUARKUS_LOG_FILE_LEVEL=DEBUG
ENGINE_LLM_LOG_ENABLED=true

# Shell: Sandbox deaktiviert (Docker kann keine Namespaces)
ENGINE_SHELL_SANDBOX_ENABLED=false

# Workspace-Settings: aktiviert (Agenten aus .opencode/agents/)
ENGINE_SETTINGS_WORKSPACE_ENABLED=true
ENGINE_WORKSPACE=/data/workspace

# Retention: kurz (Dev)
SESSION_RETENTION_DAYS=7
ENGINE_AUDIT_RETENTION_DAYS=2

# Resilience: aus (Dev)
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

### Agent-Frontmatter (dev, z.B. build-Agent)

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

### Eigenschaften
- **Alles erlaubt:** `*:* = allow` (kein Deny-Baseline)
- **Interaktive Freigaben:** `ask` blockiert für Human-Approval (UI/API)
- **Shell-Sandbox aus:** bwrap kann keine Namespaces in Docker
- **Workspace-Settings aktiv:** Agenten aus `.opencode/agents/` werden geladen
- **Logging verbose:** DEBUG-Level, LLM-Trace aktiv
- **Resilience aus:** Keine Preflight-Checks, keine Triage, kein Webhook

---

## 8. Beispiel 2: Secure-Modus (eingeschränkt, Headless/Remote)

**Ziel:** Automatisierter Betrieb ohne Human-Input, Least-Privilege, gehärtet.

### .env (secure)

```dotenv
# Provider
OPENAI_API_KEY=sk-...
ENGINE_PROVIDER_URL=https://opencode.ai/zen/go/v1/chat/completions
ENGINE_MODEL_ID=deepseek-v4.1-flash
ENGINE_PROVIDER_SCRIPTED=false

# Datenbank (Postgres)
ENGINE_DB_KIND=postgresql
QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://host.docker.internal:20000/qoc?sslmode=disable
QUARKUS_DATASOURCE_USERNAME=postgres
QUARKUS_DATASOURCE_PASSWORD=postgres

# Permissions: eingeschränkt
ENGINE_PERMISSION_DEFAULT=least-privilege
ENGINE_PERMISSION_ASK_MODE=deny

# Logging: strukturiert, JSON
QUARKUS_LOG_LEVEL=INFO
QUARKUS_LOG_CONSOLE_JSON=true
QUARKUS_LOG_FILE_JSON=true
ENGINE_LLM_LOG_ENABLED=true

# Shell: Sandbox deaktiviert (Docker kann keine Namespaces)
ENGINE_SHELL_SANDBOX_ENABLED=false

# Workspace-Settings: deaktiviert (nur globale settings.json)
ENGINE_SETTINGS_WORKSPACE_ENABLED=false
ENGINE_WORKSPACE=/data/workspace

# Retention: lang (Produktion)
# Hinweis: Code-Defaults sind SESSION_RETENTION_DAYS=7 und
# ENGINE_AUDIT_RETENTION_DAYS=2; die folgenden Werte sind ein Secure-Beispiel.
SESSION_RETENTION_DAYS=14
ENGINE_AUDIT_RETENTION_DAYS=30

# Resilience: aktiv
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

### settings.jsonc (secure, projekt-level)

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

### Agent-Frontmatter (secure, z.B. build-Agent)

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

### Eigenschaften
- **Least-Privilege:** `*:* = deny` Baseline, nur explizit erlaubte Aktionen
- **Kein Human-Input:** `ask` → auto-deny, keine HITL-Blockierung
- **Workspace-Settings deaktiviert:** Nur globale `settings.json` definiert Agenten/Permissions
- **JSON-Logging:** Strukturierte Logs für Log-Aggregation (ELK, Grafana Loki)
- **Resilience aktiv:** Preflight-Checks, Timeouts, Loop-Detection, Webhook-Alerting
- **Retention lang:** 14 Tage Sessions, 30 Tage Audit
- **Read-only Filesystem:** Container läuft als non-root, read-only Root, tmpfs für /data und /tmp

## 9. settings.json vs. settings.jsonc

| | `settings.json` (global) | `settings.jsonc` (project) |
|---|---|---|
| **Ort** | `~/.config/opencode/settings.json` | `<workspace>/settings.jsonc` |
| **Scope** | User-Level (alle Projekte) | Projekt-Level (nur dieses Projekt) |
| **Definiert** | Agents, Permissions, Skills, MCP, Guardrails | Skills (paths), Permissions (skill/subagent allow) |
| **Merge** | Wird zuerst geladen | Überschreibt non-security keys |
| **Sicherheit** | Immer aktiv | Kann deaktiviert werden (`ENGINE_SETTINGS_WORKSPACE_ENABLED=false`) |
| **Format** | JSON | JSONC (mit Kommentaren) |

## 10. Agent-Frontmatter: Modus und Berechtigungen

### Modus (`mode`)
| Wert | Bedeutung |
|---|---|
| `all` | Primär (direkt wählbar) UND Subagent (von Orchestrator startbar) |
| `primary` | Nur direkt wählbar, **nicht** als Subagent startbar |
| `subagent` | Nur als Subagent startbar, nicht direkt wählbar |

### Berechtigungs-Override (last-match-wins)
Agent-spezifische Regeln werden **nach** der Baseline angehängt und überschreiben sie:
```yaml
permissions:
  - action: "*"          # Baseline: deny all
    resource: "*"
    effect: deny
  - action: read         # Override: read allow
    resource: "*"
    effect: allow
  - action: edit         # Override: edit deny (noch restriktiver)
    resource: "*"
    effect: deny
  - action: edit         # Override: edit allow für src/
    resource: "src/**"
    effect: allow
```

## 11. Workspace-Scoping im OIDC-Modus (User vs. Admin)

### 11.1 Übersicht

Im OIDC-Modus (`ENGINE_SECURITY_MODE=oidc`) wird der Workspace-Zugriff
rollenbasiert eingeschränkt. Der Workspace-Pfad wird pro Session anhand der
Principal-Rollen bestimmt:

| Rolle | Workspace-Pfad | Zugriff |
|---|---|---|
| **Admin** (`admin`, `qoc-admin`) | `/data/workspace` (global) | Voller Zugriff auf alle Projekte und Workspaces |
| **User** (`user`, `qoc-user`) | `/data/workspace/user-ws/{userId}` | Nur eigener Workspace-Bereich |

### 11.1a Unterscheidung: User-ID vs. User-Rolle

QOC unterscheidet zwei Konzepte:

| Konzept | Feld | Verwendung | Beispiel |
|---|---|---|---|
| **User-ID** | `Principal.id` | Workspace-Pfad (`user-ws/{userId}`), Session-Zuordnung | `alice`, `bob`, `admin1` |
| **User-Rolle** | `Principal.roles` | Agent-Sichtbarkeit (`rolesAllowed`), Dateisystem-Zugriffsebene (admin → global, user → isoliert) | `admin`, `user`, `qoc-admin` |

- Die **User-ID** bestimmt den individuellen Workspace (`user-ws/{userId}`).
- Die **User-Rolle** bestimmt, ob der User den globalen Workspace sehen darf (Admin) oder nur seinen eigenen (User).
- Ein Admin-User hat eine eigene User-ID (z.B. `admin1`) und bekommt trotzdem `user-ws/admin1` als Session-Workspace, kann aber über den Dateisystem-Layer (`allowedBaseFor`) auf den globalen Workspace zugreifen.
- Die Rollen werden bei Session-Erstellung in `sessionRoles` gespeichert und an Subagents weitergegeben (für Autorisierung).

### 11.2 Wie es funktioniert

1. **Authentifizierung:** Der User meldet sich via OIDC (Keycloak/Auth0) an.
   Der Token enthält die Rolle (`user` oder `admin`).

2. **Workspace-Auflösung:** Bei Session-Erstellung (`POST /api/session`) wird
   der Workspace-Pfad anhand der Principal-Rollen bestimmt:
   - Admin → `/data/workspace` (globaler Workspace)
   - User → `/data/workspace/user-ws/{userId}` (isoliert pro User)

3. **Dateisystem-Grenze:** Der `FileAccess`-Layer prüft bei jedem
   `read`/`write`/`edit`/`patch`/`glob`/`grep`-Aufruf, ob der Pfad innerhalb
   des zugewiesenen Workspace liegt. Zugriffe außerhalb werden abgelehnt
   (`external_directory` Permission).

4. **Shell-Sandbox:** Der Shell-Scanner prüft bei jedem Shell-Befehl, ob
   die referenzierten Verzeichnisse innerhalb des Workspace liegen. Befehle
   die außerhalb zugreifen, werden blockiert.

### 11.3 Konfiguration

```properties
# OIDC aktivieren
ENGINE_SECURITY_MODE=oidc
QUARKUS_OIDC_ENABLED=true
QUARKUS_OIDC_AUTH_SERVER_URL=http://keycloak:8080/realms/qoc
QUARKUS_OIDC_TOKEN_ISSUER=http://localhost:8180/realms/qoc
QUARKUS_OIDC_TOKEN_AUDIENCE=qoc-engine

# Workspace-Scoping
ENGINE_WORKSPACE=/data/workspace              # Basis-Workspace (Admin)
ENGINE_USER_WORKSPACE_TEMPLATE=/data/workspace/user-ws/{userId}  # User-Workspace
```

### 11.4 Verhalten im Detail

| Aktion | Admin | User |
|---|---|---|
| `read /data/workspace/project-a/...` | ✅ erlaubt | ❌ denied (außerhalb eigenem Workspace) |
| `read /data/workspace/user-ws/alice/...` | ✅ erlaubt (globaler Zugriff) | ✅ nur für alice |
| `write /data/workspace/project-a/...` | ✅ erlaubt | ❌ denied |
| `shell "ls /data/workspace"` | ✅ erlaubt | ❌ denied (external_directory) |
| `shell "ls /data/workspace/user-ws/alice"` | ✅ erlaubt | ✅ erlaubt |
| Agent-Auswahl | Alle Agenten | Nur erlaubte Agenten (rolesAllowed) |
| Session-Zugriff | Alle Sessions | Nur eigene Sessions |

### 11.5 Sicherheitsgarantien

1. **Dateisystem-Isolation:** User können nicht auf Workspaces anderer User oder
   den globalen Workspace zugreifen. Der `FileAccess`-Layer prüft jeden Pfad
   gegen den zugewiesenen Workspace-Root.

2. **Shell-Isolation:** Der Shell-Scanner extrahiert alle Pfad-Referenzen aus
   Shell-Befehlen und prüft sie gegen den Workspace-Root. Befehle die
   außerhalb zugreifen, werden blockiert (`external_directory` Permission).

3. **Agent-Isolation:** User sehen nur Agenten, für die sie autorisiert sind
   (`rolesAllowed`). Admin-Agenten sind für User nicht sichtbar.

4. **Session-Isolation:** User können nur auf ihre eigenen Sessions zugreifen.
   Admins können auf alle Sessions zugreifen.

5. **Permission-Isolation:** Jede Session hat eigene Permission-Rules. User-
   Sessions können nicht die Permissions von Admin-Sessions erben.

### 11.6 Entwicklung vs. Produktion

| Aspekt | Entwicklung (Keycloak lokal) | Produktion (Auth0) |
|---|---|---|
| IdP | Keycloak (Port 8180) | Auth0 |
| Test-User | `user`/`user`, Rolle `user` | Echter Tenant-User |
| Admin-User | `admin`/`admin`, Rolle `admin` | Echter Admin-Account |
| Workspace-Scoping | Aktiv (User → `/data/workspace/user-ws/{userId}`) | Aktiv |
| OIDC-Overlay | `docker-compose.oidc.yml` | Native OIDC-Config |

### 11.7 Bekannte Einschränkungen

- **Kein Multi-Tenancy:** Alle User teilen sich eine QOC-Instanz. Workspace-
  Isolation erfolgt auf Dateisystem-Ebene, nicht auf Datenbank-Ebene.
- **Kein Quota-Management:** User können unbegrenzt Sessions/Workspaces erstellen.
- **Kein Audit-Trail pro User:** Audit-Logs sind global, nicht pro User gefiltert.
- **Admin-Zugriff:** Admins haben vollen Zugriff auf alle Workspaces. Es gibt
  keine feingranulare Admin-Berechtigung (z.B. "nur lesen, nicht schreiben").

---

## 12. Relevante Dateien

| Datei | Zweck |
|---|---|
| `.env` / `.env.example` | Infrastruktur-Config (Secrets, DB, Provider, Ports, Resilience) |
| `application.properties` | Quarkus-Config-Defaults und Config-Keys |
| `~/.config/opencode/settings.json` | Globale Settings (Agents, Permissions, Skills, MCP) |
| `<workspace>/settings.jsonc` | Projekt-Settings (Skills, Subagent-Allow) |
| `<workspace>/.opencode/agents/*.md` | Agent-Definitionen (Frontmatter + System-Prompt) |
| `<workspace>/.opencode/skills/` | Skill-Bibliotheken |
| `EngineConfig.java` | Config-Parsing, Baseline-Agents, Settings-Merge |
| `ContextBuilder.java` | leastPrivilegeDefaults(), allowAllDefaults(), defaults() |
| `PermissionEngine.java` | decide(), assertAllowed(), resolveAskMode() |
| `PermissionRules.java` | Regel-Evaluation (last-match-wins) |
path: /home/torsten/dev/my-projects/2oc/qoc/docs/settings.md
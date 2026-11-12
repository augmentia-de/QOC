# Workflow-OIDC – RBAC, Systemuser und OIDC-Integration für Workflows

> Status: Plan (2026-11-12)
> Ergänzt: `Workflow.md`, `06-security-hardening.md`, `security.md`, `settings.md`

## 1. Zielbild

Workflows, Agents und Runs im Workflow-Runner werden durch dieselbe
Authentifizierungs- und Autorisierungsinfrastruktur abgesichert wie der
Quarkus-Engine-Kern (OIDC/Basic/Proxy/NONE). Es gibt keine eigene
Nutzerverwaltung; Identitäten stammen aus dem IdP (OIDC) oder aus
Basic/Proxy-Headern. Ein Systemuser (Service-Account) ermöglicht
Automatisierung und Background-Jobs ohne interaktiven Login.

## 2. Auth-Modi (wiederverwendet aus QOC)

| Modus | Quelle | Principal.id | Rollen |
|---|---|---|---|
| `none` | kein Login | `local` (localAdmin) oder `anonymous` | `admin` (localAdmin) |
| `basic` | `OPENCODE_SERVER_PASSWORD` | `opencode` | `admin` |
| `oidc` | JWT (Keycloak/Auth0) | `preferred_username` / Subject / E-Mail | aus `realm_access.roles`, `permissions`, `roles`, `groups` |
| `proxy` | `X-Forwarded-*`-Header (trusted) | Header-User | Header-Roles |
| `mcp_oauth` | OAuth für MCP-Server | — | — |

Modus-Auflösung: `ENGINE_SECURITY_MODE` → `settings.json` (`security.mode`) →
Fallback `BASIC` wenn Passwort gesetzt, sonst `NONE`.

## 3. Rollen

| Rolle | Alias | Bedeutung |
|---|---|---|
| `admin` | `qoc-admin` | Vollzugriff, Authoring, User-Verwaltung |
| `user` | — | Eigene Ressourcen CRUD, geteilte lesen |
| `system` | — | Service-Account für Background/Automation |
| `viewer` / `reader` | — | Read-only (optional) |

Rollen kommen ausschließlich aus dem IdP/Proxy/Basic — keine eigene User-DB
(ADR-004: keine Anwendungs-DB).

## 4. Systemuser

### 4.1 Konzept

Ein synthetischer Principal, der für Automatisierung, Scheduler und
Background-Jobs verwendet wird:

```java
Principal.system()  // id="system", name="system", roles=["system"], source="system"
```

**Hinweis:** `Principal.system()` existiert noch nicht in `engine.core.model.Principal`
(nur `anonymous()` und `localAdmin()`). Muss ergänzt werden (analog zu
`localAdmin()`, aber mit Rolle `system`).

### 4.2 Authentifizierung

| Modus | Mechanismus |
|---|---|
| `none` | `Principal.system()` wird automatisch für Background-Jobs gesetzt (analog zu `localAdmin()` für interaktive Calls). |
| `basic`/`oidc`/`proxy` | Service-Token via `X-System-Token`-Header, validiert gegen `ENGINE_SYSTEM_TOKEN` (konfigurierbar). Alternativ OIDC Client-Credentials Flow. |

### 4.3 Konfiguration

```properties
ENGINE_SYSTEM_TOKEN=${ENGINE_SYSTEM_TOKEN:}        # Service-Token
ENGINE_SYSTEM_ROLES=${ENGINE_SYSTEM_ROLES:system}  # Rollen (kommasepariert)
ENGINE_SYSTEM_OWNERSHIP=${ENGINE_SYSTEM_OWNERSHIP:system}  # system | caller
```

### 4.4 Verwendung

- Workflow-Runs, die durch Scheduler/Webhooks/Events ausgelöst werden, laufen
  als `system`.
- `WorkflowExecutor.executeRun` nutzt `Principal.system()` statt `anonymous()`.
- Erstellte Workflows/Agents erhalten `ownerId = "system"` (oder den
  auslösenden User bei `ENGINE_SYSTEM_OWNERSHIP=caller`).

## 5. Ownership-Modell

### 5.1 Datenmodell-Erweiterungen

- `WorkflowDefinition`: neues Feld `ownerId` (String, nullable = global/shared).
- `WorkflowDefinition.Agent`: neues Feld `ownerId` **und** `rolesAllowed`
  (List<String>, Default `["*"]` für geteilte Agents — analog zu
  `EngineConfig.AgentConfig.rolesAllowed()`).
- `SessionInfo` hat bereits `ownerId` — Workflow-Runs erzeugen Sessions mit
  der ownerId des auslösenden Users (oder `system`).

### 5.2 Zugriffsregeln

| Ressource | Admin/System | User | Viewer | Anonymous (NONE) |
|---|---|---|---|---|
| Workflow list | alle | eigene + shared (ownerId=null) | alle (read) | alle (lokal admin) |
| Workflow get | alle | eigene + shared | alle | alle |
| Workflow create | ja | ja (owner=self) | nein | ja (owner=local/system) |
| Workflow save/delete | alle | eigene | nein | ja |
| Workflow run | alle | eigene + shared | nein | ja |
| Agent list/get | alle | eigene + shared | alle | alle |
| Agent create/save/delete | alle | eigene | nein | ja |
| Run list/status/resume | alle | eigene | eigene (read) | ja |

## 6. OIDC-Flow für Workflows

### 6.1 UI → Backend

1. UI (React) führt OIDC Authorization Code + PKCE durch (Issuer aus
   `localStorage` oder `/config.js`).
2. Access-Token wird als `Authorization: Bearer <token>` an `/api/workflows/*`
   gesendet.
3. `OidcPrincipalProducer` (Priority AUTHENTICATION-5) extrahiert Principal +
   Rollen aus dem JWT und setzt `PrincipalContext`.
4. `RolesAllowedFilter` (Priority AUTHORIZATION) prüft `@RolesAllowed`.

### 6.2 Backend → Workflow-Runner

1. `WorkflowResource` liest `X-QOC-User` (oder `accessGuard.currentUser()`).
2. `WorkflowExecutor.executeRun(def, input, user)` erzeugt eine Session mit
   `ownerId = user` und `Principal` statt `anonymous()`.
3. `DefaultNodeDispatcher.runAgent` reicht `Principal` an `engine.runPrompt`
   durch.
4. Agent-Tool-Calls (Shell, Files, Web, MCP, Subagents) laufen unter dem
   Principal des Workflow-Runs.

### 6.3 Role-Claim-Pfad

Pro Profil konfiguriert (ADR-006):

| Profil | Claim-Pfad | Beispiel |
|---|---|---|
| `%dev` (Keycloak) | `realm_access/roles` | `["admin", "user"]` |
| `%prod` (Auth0) | namespaced Claim (z. B. `https://qoc/roles`) | `["admin", "user"]` |

Kein stiller `groups`-Fallback; fehlender Claim → 403 fail-closed.

### 6.4 Trusted Proxy

Wenn `ENGINE_SECURITY_MODE=proxy`, werden `X-Forwarded-User`,
`X-Forwarded-Roles`, `X-Forwarded-Groups` ausgewertet (nur bei trusted
Proxies, siehe `SecurityConfig.trustedProxies()`).

## 7. Implementierungsschritte

1. `Principal.system()` in `engine.core.model.Principal` hinzufügen.
2. `BasicAuthFilter`/`OidcPrincipalProducer` um System-Token-Support erweitern
   (`X-System-Token` → `ENGINE_SYSTEM_TOKEN`).
3. `SessionAccessGuard` auf `WorkflowResource` und `AgentStoreResource` anwenden
   (`@Inject`, `requireAccess`, `isAdmin`).
4. `ownerId`-Feld in `WorkflowDefinition` und `WorkflowDefinition.Agent`
   hinzufügen (inkl. `rolesAllowed`).
5. `WorkflowExecutor.executeRun(def, input, user)` Overload; `runAgent` mit
   Principal.
6. `AgentStore.listAll(userId, isAdmin)` mit ownerId-Filter.
7. `WorkflowResource`: `@HeaderParam("X-QOC-User")`, User-Durchreichung,
   Run-Filtering nach ownerId.
8. `Workflow-OIDC.md` in `docs/engineering/` ablegen.
9. Tests: Access-Guard, Ownership, Systemuser, OIDC-Principal-Extraktion.

## 8. Endpunkte (nach Änderung)

| Endpoint | Guard | Beschreibung |
|---|---|---|
| `GET /api/workflows` | `@RolesAllowed("user","admin","system","viewer")` + ownerId-Filter | Workflows des Users + shared |
| `POST /api/workflows` | `@RolesAllowed("user","admin","system")` | Erstellen (owner=self) |
| `GET /api/workflows/{id}` | `requireWorkflowAccess` | Lesen |
| `PUT /api/workflows/{id}` | `requireWorkflowAccess` + owner/admin | Speichern |
| `DELETE /api/workflows/{id}` | `requireWorkflowAccess` + owner/admin | Löschen |
| `POST /api/workflows/run` | `@RolesAllowed("user","admin","system")` | Ausführen |
| `GET /api/workflows/runs` | ownerId-Filter | Runs des Users (admin/system: alle) |
| `GET /api/workflows/runs/{id}` | `requireAccess` | Status |
| `POST /api/workflows/runs/{id}/resume` | `requireAccess` | Fortsetzen |
| `GET /api/agents` | `@RolesAllowed("user","admin","system","viewer")` + ownerId-Filter | Agents des Users + shared |
| `POST/PUT/DELETE /api/agents/{id}` | owner/admin/system | CRUD |

## 9. Konfiguration

```properties
# RBAC / OIDC (wiederverwendet aus QOC)
ENGINE_SECURITY_MODE=${ENGINE_SECURITY_MODE:}     # none | basic | oidc | proxy | mcp_oauth
QOC_OIDC_ISSUER=${QOC_OIDC_ISSUER:}
QOC_OIDC_CLIENT_ID=${QOC_OIDC_CLIENT_ID:}
QOC_OIDC_CLIENT_SECRET=${QOC_OIDC_CLIENT_SECRET:}
OPENCODE_SERVER_PASSWORD=${OPENCODE_SERVER_PASSWORD:}  # basic mode

# Systemuser
ENGINE_SYSTEM_TOKEN=${ENGINE_SYSTEM_TOKEN:}        # Service-Token
ENGINE_SYSTEM_ROLES=${ENGINE_SYSTEM_ROLES:system}  # Rollen
ENGINE_SYSTEM_OWNERSHIP=${ENGINE_SYSTEM_OWNERSHIP:system}  # system | caller

# Trusted Proxies (settings.json)
# security.trustedProxies = ["10.0.0.0/8"]
```

## 10. Audit

- Journal-Events (`WorkflowRunStarted`, `WorkflowNodeCompleted`, etc.) enthalten
  `ownerId` des auslösenden Users.
- Agent-Änderungen werden als `WorkflowAgentChanged`-Event geloggt (optional).
- Systemuser-Aktionen sind über `principalId="system"` auditierbar.

## 11. Offene Rückfragen

1. **Systemuser-Rolle**: Eigene Rolle `system` (empfohlen, auditierbar) oder
   `admin` wiederverwenden (einfacher, aber nicht unterscheidbar im Audit)?
2. **Ownership-Default**: Workflows/Agents standardmäßig `ownerId=null` (shared)
   oder `ownerId=self` (privat)? Empfehlung: `null` (shared) für Team-Workflows,
   opt-in privat.
3. **Agent-Roles im Workflow-Runner**: QOC-Agenten haben `rolesAllowed`. Sollen
   Workflow-Agenten ebenfalls `rolesAllowed` tragen? Empfehlung: ja, für
   Konsistenz mit QOC.
4. **Agent-Mode**: QOC-Agenten haben `mode` (primary/subagent/all). Soll
   Workflow-Agenten ebenfalls `mode` tragen? Empfehlung: ja, für Subagent-
   Aufrufe aus Workflows.
5. **Migration bestehender Daten**: Bestehende Workflows/Agents haben kein
   `ownerId`. Default auf `"anonymous"` oder `"local"`? Empfehlung:
   `"local"` (für NONE-Modus) bzw. admin-User.
6. **Systemuser-Authoring**: In Secure-Mode ist Agent-Authoring deaktiviert
   (`ENGINE_AGENT_AUTHORING_ENABLED=false`). Soll der Systemuser dennoch
   authoring dürfen? Empfehlung: ja, aber nur über API (nicht UI).
7. **Audit-Trail**: Soll Agent-Änderung ein eigenes Journal-Event sein
   (`AgentChanged`) oder reicht der vorhandene Audit-Log?
8. **OIDC Client-Credentials**: Statischer Token (`ENGINE_SYSTEM_TOKEN`) oder
   OIDC Client-Credentials Flow für Systemuser? Empfehlung: statischer Token
   für Einfachheit, Client-Credentials für Produktion.
9. **`Principal.system()` in engine.core**: Muss in
   `engine.core.model.Principal` ergänzt werden (aktuell nur in
   `quarkus.http.security.Principal`). Soll der engine.core-Principal auch
   `authMode`/`source`/`claims` tragen? Empfehlung: minimal (id, name, roles).
10. **Workflow-Runner als eigenständiger Service**: Der Workflow-Runner nutzt
    `engine.qocEngine()` (in-process). Soll er einen eigenen
    `SessionAccessGuard` haben oder den von QOC wiederverwenden? Empfehlung:
    wiederverwenden (bereits injizierbar).

## 12. Offene Punkte (aus QOC-Plan übernommen)

- O-02/O-13: Prod-Claim-Name (blockiert GATE 2).
- Ob Workflows geteilt oder privat sein sollen (Produktentscheidung).
- Ob Systemuser `admin`- oder eigene `system`-Rolle (Empfehlung: eigene).
- Audit-Trail für Agent-Änderungen.

## 13. Referenzen

| Thema | Pfad |
|---|---|
| SessionAccessGuard | `quarkus/http/SessionAccessGuard.java` |
| Principal (engine.core) | `engine/core/model/Principal.java` |
| Principal (quarkus) | `quarkus/http/security/Principal.java` |
| OidcPrincipalProducer | `quarkus/http/security/OidcPrincipalProducer.java` |
| BasicAuthFilter | `quarkus/http/BasicAuthFilter.java` |
| RolesAllowedFilter | `quarkus/http/security/RolesAllowedFilter.java` |
| AgentAccess | `quarkus/http/AgentAccess.java` |
| EngineConfig.AgentConfig | `quarkus/http/EngineConfig.java:719` |
| AgentStoreResource | `quarkus/http/AgentStoreResource.java` |
| WorkflowResource | `quarkus/http/WorkflowResource.java` |
| WorkflowExecutor | `workflow-runner/runner/WorkflowExecutor.java` |
| DefaultNodeDispatcher | `workflow-runner/runner/DefaultNodeDispatcher.java` |
| SessionInfo (ownerId) | `engine/core/model/SessionInfo.java` |
| WorkflowDefinition | `workflow-runner/model/WorkflowDefinition.java` |
| RBAC-Plan | `/home/torsten/.opencode/plan/rbac-workflow-oidc.md` |

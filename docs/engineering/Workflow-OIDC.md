# Workflow-OIDC – RBAC, System User, and OIDC Integration for Workflows

> Status: Plan (2026-11-12)
> Supplements: `Workflow.md`, `06-security-hardening.md`, `security.md`, `settings.md`

## 1. Goal

Workflows, agents, and runs in the workflow runner are secured by the same
authentication and authorization infrastructure as the Quarkus engine core
(OIDC/Basic/Proxy/NONE). There is no separate user management; identities
come from the IdP (OIDC) or from Basic/Proxy headers. A system user
(service account) enables automation and background jobs without interactive
login.

## 2. Auth Modes (reused from QOC)

| Mode | Source | Principal.id | Roles |
|---|---|---|---|
| `none` | no login | `local` (localAdmin) or `anonymous` | `admin` (localAdmin) |
| `basic` | `OPENCODE_SERVER_PASSWORD` | `opencode` | `admin` |
| `oidc` | JWT (Keycloak/Auth0) | `preferred_username` / Subject / Email | from `realm_access.roles`, `permissions`, `roles`, `groups` |
| `proxy` | `X-Forwarded-*` headers (trusted) | header user | header roles |
| `mcp_oauth` | OAuth for MCP servers | — | — |

Mode resolution: `ENGINE_SECURITY_MODE` → `settings.json` (`security.mode`) →
Fallback `BASIC` if a password is set, otherwise `NONE`.

## 3. Roles

| Role | Alias | Meaning |
|---|---|---|
| `admin` | `qoc-admin` | Full access, authoring, user management |
| `user` | — | CRUD on own resources, read shared ones |
| `system` | — | Service account for background/automation |
| `viewer` / `reader` | — | Read-only (optional) |

Roles come exclusively from the IdP/Proxy/Basic — no own user DB
(ADR-004: no application database).

## 4. System User

### 4.1 Concept

A synthetic principal used for automation, schedulers, and background jobs:

```java
Principal.system()  // id="system", name="system", roles=["system"], source="system"
```

**Note:** `Principal.system()` does not yet exist in `engine.core.model.Principal`
(only `anonymous()` and `localAdmin()`). It must be added (analogous to
`localAdmin()`, but with role `system`).

### 4.2 Authentication

| Mode | Mechanism |
|---|---|
| `none` | `Principal.system()` is set automatically for background jobs (analogous to `localAdmin()` for interactive calls). |
| `basic`/`oidc`/`proxy` | Service token via `X-System-Token` header, validated against `ENGINE_SYSTEM_TOKEN` (configurable). Alternatively, the OIDC client-credentials flow. |

### 4.3 Configuration

```properties
ENGINE_SYSTEM_TOKEN=${ENGINE_SYSTEM_TOKEN:}        # Service token
ENGINE_SYSTEM_ROLES=${ENGINE_SYSTEM_ROLES:system}  # Roles (comma-separated)
ENGINE_SYSTEM_OWNERSHIP=${ENGINE_SYSTEM_OWNERSHIP:system}  # system | caller
```

### 4.4 Usage

- Workflow runs triggered by schedulers/webhooks/events run as `system`.
- `WorkflowExecutor.executeRun` uses `Principal.system()` instead of `anonymous()`.
- Created workflows/agents receive `ownerId = "system"` (or the triggering
  user when `ENGINE_SYSTEM_OWNERSHIP=caller`).

## 5. Ownership Model

### 5.1 Data Model Extensions

- `WorkflowDefinition`: new field `ownerId` (String, nullable = global/shared).
- `WorkflowDefinition.Agent`: new field `ownerId` **and** `rolesAllowed`
  (List<String>, default `["*"]` for shared agents — analogous to
  `EngineConfig.AgentConfig.rolesAllowed()`).
- `SessionInfo` already has `ownerId` — workflow runs create sessions with
  the ownerId of the triggering user (or `system`).

### 5.2 Access Rules

| Resource | Admin/System | User | Viewer | Anonymous (NONE) |
|---|---|---|---|---|
| Workflow list | all | own + shared (ownerId=null) | all (read) | all (local admin) |
| Workflow get | all | own + shared | all | all |
| Workflow create | yes | yes (owner=self) | no | yes (owner=local/system) |
| Workflow save/delete | all | own | no | yes |
| Workflow run | all | own + shared | no | yes |
| Agent list/get | all | own + shared | all | all |
| Agent create/save/delete | all | own | no | yes |
| Run list/status/resume | all | own | own (read) | yes |

## 6. OIDC Flow for Workflows

### 6.1 UI → Backend

1. The UI (React) performs the OIDC Authorization Code + PKCE flow (issuer
   from `localStorage` or `/config.js`).
2. The access token is sent as `Authorization: Bearer <token>` to
   `/api/workflows/*`.
3. `OidcPrincipalProducer` (priority AUTHENTICATION-5) extracts the principal
   + roles from the JWT and sets `PrincipalContext`.
4. `RolesAllowedFilter` (priority AUTHORIZATION) checks `@RolesAllowed`.

### 6.2 Backend → Workflow-Runner

1. `WorkflowResource` reads `X-QOC-User` (or `accessGuard.currentUser()`).
2. `WorkflowExecutor.executeRun(def, input, user)` creates a session with
   `ownerId = user` and `Principal` instead of `anonymous()`.
3. `DefaultNodeDispatcher.runAgent` passes `Principal` through to
   `engine.runPrompt`.
4. Agent tool calls (Shell, Files, Web, MCP, Subagents) run under the
   principal of the workflow run.

### 6.3 Role Claim Path

Configured per profile (ADR-006):

| Profile | Claim path | Example |
|---|---|---|
| `%dev` (Keycloak) | `realm_access/roles` | `["admin", "user"]` |
| `%prod` (Auth0) | namespaced claim (e.g. `https://qoc/roles`) | `["admin", "user"]` |

No silent `groups` fallback; missing claim → 403 fail-closed.

### 6.4 Trusted Proxy

When `ENGINE_SECURITY_MODE=proxy`, `X-Forwarded-User`,
`X-Forwarded-Roles`, `X-Forwarded-Groups` are evaluated (only for trusted
proxies, see `SecurityConfig.trustedProxies()`).

## 7. Implementation Steps

1. Add `Principal.system()` to `engine.core.model.Principal`.
2. Extend `BasicAuthFilter`/`OidcPrincipalProducer` with system-token support
   (`X-System-Token` → `ENGINE_SYSTEM_TOKEN`).
3. Apply `SessionAccessGuard` to `WorkflowResource` and `AgentStoreResource`
   (`@Inject`, `requireAccess`, `isAdmin`).
4. Add `ownerId` field to `WorkflowDefinition` and `WorkflowDefinition.Agent`
   (incl. `rolesAllowed`).
5. `WorkflowExecutor.executeRun(def, input, user)` overload; `runAgent` with
   principal.
6. `AgentStore.listAll(userId, isAdmin)` with ownerId filter.
7. `WorkflowResource`: `@HeaderParam("X-QOC-User")`, user propagation, run
   filtering by ownerId.
8. Place `Workflow-OIDC.md` in `docs/engineering/`.
9. Tests: access guard, ownership, system user, OIDC principal extraction.

## 8. Endpoints (after changes)

| Endpoint | Guard | Description |
|---|---|---|
| `GET /api/workflows` | `@RolesAllowed("user","admin","system","viewer")` + ownerId filter | Workflows of the user + shared |
| `POST /api/workflows` | `@RolesAllowed("user","admin","system")` | Create (owner=self) |
| `GET /api/workflows/{id}` | `requireWorkflowAccess` | Read |
| `PUT /api/workflows/{id}` | `requireWorkflowAccess` + owner/admin | Save |
| `DELETE /api/workflows/{id}` | `requireWorkflowAccess` + owner/admin | Delete |
| `POST /api/workflows/run` | `@RolesAllowed("user","admin","system")` | Execute |
| `GET /api/workflows/runs` | ownerId filter | Runs of the user (admin/system: all) |
| `GET /api/workflows/runs/{id}` | `requireAccess` | Status |
| `POST /api/workflows/runs/{id}/resume` | `requireAccess` | Resume |
| `GET /api/agents` | `@RolesAllowed("user","admin","system","viewer")` + ownerId filter | Agents of the user + shared |
| `POST/PUT/DELETE /api/agents/{id}` | owner/admin/system | CRUD |

## 9. Configuration

```properties
# RBAC / OIDC (reused from QOC)
ENGINE_SECURITY_MODE=${ENGINE_SECURITY_MODE:}     # none | basic | oidc | proxy | mcp_oauth
QOC_OIDC_ISSUER=${QOC_OIDC_ISSUER:}
QOC_OIDC_CLIENT_ID=${QOC_OIDC_CLIENT_ID:}
QOC_OIDC_CLIENT_SECRET=${QOC_OIDC_CLIENT_SECRET:}
OPENCODE_SERVER_PASSWORD=${OPENCODE_SERVER_PASSWORD:}  # basic mode

# System user
ENGINE_SYSTEM_TOKEN=${ENGINE_SYSTEM_TOKEN:}        # Service token
ENGINE_SYSTEM_ROLES=${ENGINE_SYSTEM_ROLES:system}  # Roles
ENGINE_SYSTEM_OWNERSHIP=${ENGINE_SYSTEM_OWNERSHIP:system}  # system | caller

# Trusted Proxies (settings.json)
# security.trustedProxies = ["10.0.0.0/8"]
```

## 10. Audit

- Journal events (`WorkflowRunStarted`, `WorkflowNodeCompleted`, etc.) include
  the `ownerId` of the triggering user.
- Agent changes are logged as `WorkflowAgentChanged` event (optional).
- System user actions are auditable via `principalId="system"`.

## 11. Open Questions

1. **System user role**: dedicated role `system` (recommended, auditable) or
   reuse `admin` (simpler, but not distinguishable in audit)?
2. **Ownership default**: workflows/agents default `ownerId=null` (shared) or
   `ownerId=self` (private)? Recommendation: `null` (shared) for team
   workflows, opt-in private.
3. **Agent roles in the workflow runner**: QOC agents have `rolesAllowed`.
   Should workflow agents also carry `rolesAllowed`? Recommendation: yes, for
   consistency with QOC.
4. **Agent mode**: QOC agents have `mode` (primary/subagent/all). Should
   workflow agents also carry `mode`? Recommendation: yes, for subagent calls
   from workflows.
5. **Migration of existing data**: existing workflows/agents have no
   `ownerId`. Default to `"anonymous"` or `"local"`? Recommendation:
   `"local"` (for NONE mode) or admin user.
6. **System user authoring**: in secure mode agent authoring is disabled
   (`ENGINE_AGENT_AUTHORING_ENABLED=false`). Should the system user still be
   allowed to author? Recommendation: yes, but only via API (not UI).
7. **Audit trail**: should agent changes be a dedicated journal event
   (`AgentChanged`) or is the existing audit log sufficient?
8. **OIDC client credentials**: static token (`ENGINE_SYSTEM_TOKEN`) or OIDC
   client-credentials flow for the system user? Recommendation: static token
   for simplicity, client credentials for production.
9. **`Principal.system()` in engine.core**: must be added to
   `engine.core.model.Principal` (currently only in
   `quarkus.http.security.Principal`). Should the engine.core principal also
   carry `authMode`/`source`/`claims`? Recommendation: minimal (id, name,
   roles).
10. **Workflow runner as a standalone service**: the workflow runner uses
    `engine.qocEngine()` (in-process). Should it have its own
    `SessionAccessGuard` or reuse QOC's? Recommendation: reuse (already
    injectable).

## 12. Open Items (carried over from the QOC plan)

- O-02/O-13: prod claim name (blocks GATE 2).
- Whether workflows should be shared or private (product decision).
- Whether the system user uses the `admin` role or its own `system` role
  (recommendation: own).
- Audit trail for agent changes.

## 13. References

| Topic | Path |
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
| RBAC plan | `/home/torsten/.opencode/plan/rbac-workflow-oidc.md` |
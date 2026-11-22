# RBAC + Workflow-OIDC – Detailed Implementation Plan

> **Status:** Partially implemented (2026-11-22) — Steps 1, 2, 4, 5, 6, 7 done; Step 8 partial (ownerId only in WorkflowRunStarted); Steps 9–12 not done.

> Location: `/home/torsten/.opencode/plan/rbac-implementation-plan.md`
> Target location (code mode): `qoc/docs/plans/rbac-workflow-oidc-implementation.md`
> Reference: `qoc/docs/engineering/Workflow-OIDC.md`

## Planned Security Features

This plan implements the following security features for the workflow runner,
which are currently missing or only partially present:

1. **Authentication (AuthN)** – reuse of QOC auth modes:
   - `none` (local, no login), `basic` (HTTP Basic), `oidc` (Keycloak/Auth0),
     `proxy` (Trusted-Proxy), `mcp_oauth`.
   - System-token auth via the `X-System-Token` header for service accounts.
   - OIDC principal extraction from JWT (roles, groups, claims).

2. **Authorization (AuthZ) – RBAC**:
   - Roles: `admin`/`qoc-admin`, `user`, `system`, `viewer`/`reader`.
   - `@RolesAllowed` annotations on all workflow and agent endpoints.
   - `SessionAccessGuard` for ownership checks (only owner/admin/system may
     access).
   - Agent-level access via `rolesAllowed` (analogous to `EngineConfig.AgentConfig`).

3. **Ownership model**:
   - `ownerId` on `WorkflowDefinition` and `WorkflowDefinition.Agent`.
   - Runs, workflows, and agents are user-scoped (own + shared).
   - System user (`Principal.system()`) for automatically created resources.

4. **System user (service account)**:
   - Synthetic principal with role `system`.
   - For schedulers, webhooks, background jobs without interactive login.
   - Configurable: `ENGINE_SYSTEM_TOKEN`, `ENGINE_SYSTEM_ROLES`,
     `ENGINE_SYSTEM_OWNERSHIP`.

5. **OIDC integration for workflows**:
   - Token propagation: UI → `/api/workflows/*` → `WorkflowExecutor` →
     `QocEngine.runPrompt` (principal instead of anonymous).
   - Role-claim path per profile (Keycloak `realm_access/roles`, Auth0
     namespaced).
   - Trusted-proxy auth (`X-Forwarded-*`).
   - Fail-closed on missing claims (403).

6. **Audit & traceability**:
   - `ownerId` in journal events (`WorkflowRunStarted`, `WorkflowNodeCompleted`,
     `WorkflowRunFinished`).
   - System-user actions auditable via `principalId="system"`.
   - Optional: `WorkflowAgentChanged` event for agent changes.

7. **Migration & backward compatibility**:
   - Feature flag `ENGINE_WORKFLOW_RBAC_ENABLED` (default `false`).
   - Existing data keeps default `ownerId` (`"local"`).
   - `executeRun(def, input)` (without user) delegates with
     `Principal.anonymous()` → no breaking change.

8. **Security levels** (from `06-security-hardening.md`):
   - Least-privilege as default (`engine.permission.default=least-privilege`).
   - Shell sandbox (bwrap, `workspace-write`).
   - Short-lived tool tokens (60 s).
   - Container hardening (read-only FS, `cap_drop ALL`, Seccomp).

## Overview

12 implementation steps across 4 phases. Each step has: goal, files,
code changes, tests, commit. Tests must be green before the commit.

---


## Implementation Status (2026-11-22)

### Implemented
- **Step 1** `Principal.system()` — `engine/core/model/Principal.java`
- **Step 2** System-token auth (`X-System-Token` header) — `BasicAuthFilter.java` + config keys
- **Step 4** `ownerId` in `WorkflowDefinition` (default `""`) and `rolesAllowed` in `Agent` (default `List.of()`, `"*"` = all)
- **Step 5** `WorkflowExecutor` overloads `executeRun(def, input, String user)` and `executeRun(def, input, Principal)`; `DefaultNodeDispatcher.runAgent` threads Principal
- **Step 6** `SessionAccessGuard` guards in `WorkflowResource`: `requireWorkflowAccess`, `isWorkflowOwner`, `X-QOC-User` header, run filtering by ownerId
- **Step 7** `SessionAccessGuard` guards in `AgentStoreResource`: `requireAgentAccess`, ownerId filtering on list/create/save/delete
- **Step 8 (partial)** `WorkflowRunStarted` event includes `ownerId`; `WorkflowNodeCompleted` and `WorkflowRunFinished` do **not** have `ownerId`

### Not implemented
- **Step 3** `@RolesAllowed` for `system` role — documentation only (no code change needed)
- **Step 8 (partial)** `ownerId` missing in `WorkflowNodeCompleted` and `WorkflowRunFinished` journal events
- **Step 9** `@RolesAllowed` annotations on `WorkflowResource` and `AgentStoreResource` endpoints — not present
- **Step 10** System-token unit tests — not found
- **Step 11** Ownership integration tests — not found
- **Step 12** Documentation (`CONFIG.md` system-user keys) — not verified

### Deviations
- Agent `rolesAllowed` default is `List.of()` (nobody) instead of `List.of("*")` (all) as originally specified. Changed to least-privilege default: empty = nobody, `"*"` = all.

## Phase 1: Foundation (System User + Principal)

### Step 1: `Principal.system()` in engine.core

**Goal:** Make the system-user principal available in the core.

**Files:**
- `engine/src/main/java/de/augmentia/oc/engine/core/model/Principal.java`

**Change:**
```java
public static Principal system() {
    return new Principal("system", "system", List.of("system"), List.of(), Map.of(), null);
}
```

**Tests:**
- `PrincipalTest` (new or extend): `Principal.system().id() == "system"`, `roles().contains("system")`, `isAdmin() == false`.

**Commit:** `feat(engine): add Principal.system() for service-account principal`

---

### Step 2: System-token auth in BasicAuthFilter

**Goal:** Validate the `X-System-Token` header against `ENGINE_SYSTEM_TOKEN` and
set `Principal.system()`.

**Files:**
- `quarkus/src/main/java/de/augmentia/oc/engine/http/BasicAuthFilter.java`
- `quarkus/src/main/resources/application.properties` (new keys)

**Change:**
- New config: `engine.system.token`, `engine.system.roles`.
- In `filter()`: if the header `X-System-Token` is present and matches
  `engine.system.token` → `principalContext.set(new Principal("system", "system", systemRoles, ...))`.
- Insert before the basic-auth check.

**Tests:**
- `BasicAuthFilterTest` (extend): valid system token → `Principal.system()`;
  invalid → 401; missing → fallback to Basic/OIDC.

**Commit:** `feat(security): system-token auth via X-System-Token header`

---

### Step 3: `@RolesAllowed` for the `system` role

**Goal:** `RolesAllowedFilter` recognizes `system` as a valid role.

**Files:**
- `quarkus/src/main/java/de/augmentia/oc/engine/http/security/RolesAllowedFilter.java`

**Change:** Already generic (checks `userRoles.contains(req)`). No change
needed — `system` is recognized automatically. Documentation only.

**Tests:** no new tests.

---

## Phase 2: Ownership Model (Data)

### Step 4: `ownerId` in `WorkflowDefinition` and `Agent`

**Goal:** Ownership field in the models.

**Files:**
- `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/model/WorkflowDefinition.java`

**Change:**
- `WorkflowDefinition` record: new field `String ownerId` (nullable, default null).
- `WorkflowDefinition.Agent` record: new field `String ownerId` (nullable) and
  `List<String> rolesAllowed` (default `List.of("*")`).
- Compact constructors: null handling.
- `WorkflowStore` (file-based): round-trip (Jackson serializes automatically).
- `AgentStore.save()`: pass through ownerId/rolesAllowed (like responseSchema).

**Tests:**
- `WorkflowDefinitionTest` (if present) or new test:
  serialization/deserialization with ownerId and rolesAllowed.
- `AgentStoreTest` (new): save/get with ownerId.

**Commit:** `feat(workflow): add ownerId and rolesAllowed to WorkflowDefinition and Agent`

---

### Step 5: `Principal` in `WorkflowExecutor` and `DefaultNodeDispatcher`

**Goal:** Thread user/Principal instead of `anonymous()`.

**Files:**
- `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/WorkflowExecutor.java`
- `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/DefaultNodeDispatcher.java`

**Change:**
- `WorkflowExecutor`: new overload `executeRun(def, input, String user)` and
  `executeRun(def, input, Principal principal)`. Existing methods delegate
  with `Principal.anonymous()`.
- `executeRun`: `engine.createSession("workflow:" + workflowId, user, "workspace")`
  instead of hardcoded `"workflow"`.
- Set `def.ownerId` if null (from user).
- `WorkflowExecutor.run(session, runId, ...)` → `ctx` or `sink` with ownerId.
- `DefaultNodeDispatcher`: `runAgent` uses `engine.runPrompt(child, ..., principal)`
  instead of `Principal.anonymous()`. Principal as field or parameter.

**Tests:**
- `WorkflowExecutorTest` (extend): executeRun with user → Session ownerId == user.
- `DefaultNodeDispatcherTest` (if present): runAgent uses the passed Principal.

**Commit:** `feat(workflow): thread Principal/user through WorkflowExecutor and NodeDispatcher`

---

## Phase 3: Access Control (Guards)

### Step 6: Guards in `WorkflowResource`

**Goal:** Apply `SessionAccessGuard` to workflow endpoints.

**Files:**
- `quarkus/src/main/java/de/augmentia/oc/engine/http/WorkflowResource.java`

**Change:**
- `@Inject SessionAccessGuard accessGuard;`
- `listWorkflows()`: filter by ownerId (admin/system see all).
- `getWorkflow(id)`: `accessGuard.requireAccess` (or new `requireWorkflowAccess`).
- `saveWorkflow(id, def)`: owner/admin/system.
- `deleteWorkflow(id)`: owner/admin/system.
- `run(request)`: `@HeaderParam("X-QOC-User")`, `accessGuard.requireAgent` for embedded agents, user propagation.
- `listRuns`, `runStatus`, `resumeRun`: ownerId filter / requireAccess.

**New helpers in `SessionAccessGuard`:**
- `requireWorkflowAccess(String workflowId, String ownerId)` → 403 if not owner/admin/system.
- `isWorkflowOwner(ownerId, current)` → boolean.

**Tests:**
- `WorkflowResourceTest` (new or extend): 403 for non-owner, 200 for owner/admin, run filtering.

**Commit:** `feat(workflow): apply SessionAccessGuard to WorkflowResource endpoints`

---

### Step 7: Guards in `AgentStoreResource`

**Goal:** Secure agent-store endpoints.

**Files:**
- `quarkus/src/main/java/de/augmentia/oc/engine/http/AgentStoreResource.java`

**Change:**
- `@Inject SessionAccessGuard accessGuard;`
- `list()`: ownerId filter (admin/system see all).
- `get(id)`: requireAgentAccess.
- `create(body)`: owner = currentUser (or system).
- `save(id, body)`: owner/admin/system.
- `delete(id)`: owner/admin/system.

**AgentStore change:**
- `AgentStore.listAll(userId, isAdmin)` overload; existing `listAll()` delegates with `null, true` (backward-compat).

**Tests:**
- `AgentStoreResourceTest` (new): 403/200, ownerId filter.

**Commit:** `feat(workflow): apply SessionAccessGuard to AgentStoreResource`

---

## Phase 4: Audit & Tests

### Step 8: Journal events with ownerId

**Goal:** Add `ownerId` to workflow-run events.

**Files:**
- `engine/src/main/java/de/augmentia/oc/engine/core/model/SessionEvent.java`
- `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/JournalWorkflowEventSink.java`

**Change:**
- Extend `WorkflowRunStarted` / `WorkflowNodeCompleted` / `WorkflowRunFinished`
  with an `ownerId` field (nullable).
- `JournalWorkflowEventSink`: pass ownerId from Session/Principal.

**Tests:**
- `WorkflowRunJournalTest` (extend): ownerId in events.

**Commit:** `feat(workflow): include ownerId in workflow journal events`

---

### Step 9: `@RolesAllowed` annotations

**Goal:** JAX-RS security annotations on endpoints.

**Files:**
- `WorkflowResource.java`, `AgentStoreResource.java`

**Change:**
- `@RolesAllowed("user", "admin", "system", "viewer")` on GET endpoints.
- `@RolesAllowed("user", "admin", "system")` on POST/PUT/DELETE.

**Tests:**
- Extend `RolesAllowedFilter` tests (if present).

**Commit:** `feat(workflow): add @RolesAllowed to workflow and agent endpoints`

---

### Step 10: System-token tests

**Goal:** Unit tests for system-token auth.

**Files:**
- `quarkus/src/test/java/.../BasicAuthFilterTest.java` (extend)

**Tests:**
- Valid system token → `Principal.id() == "system"`, roles contains `"system"`.
- Invalid token → 401.
- Missing token → fallback to Basic/OIDC/NONE.

**Commit:** `test(security): system-token auth tests`

---

### Step 11: Ownership tests

**Goal:** Integration tests for user isolation.

**Files:**
- `quarkus/src/test/java/.../WorkflowOwnershipTest.java` (new)

**Tests:**
- User A creates workflow → User B gets 403 on get/save/delete/run.
- Admin sees all workflows.
- System user creates workflow → ownerId = "system".
- Shared workflow (ownerId=null) readable by all authenticated users.

**Commit:** `test(workflow): ownership isolation integration tests`

---

### Step 12: Documentation

**Goal:** Workflow-OIDC.md in the engineering folder (already created), update CONFIG.md.

**Files:**
- `qoc/docs/CONFIG.md` (add system-user keys)
- `qoc/docs/engineering/Workflow-OIDC.md` (already created)

**Tests:** none.

**Commit:** `docs: add systemuser and RBAC config to CONFIG.md`

---

## Migration Strategy

1. **Existing data**: Workflows/agents without `ownerId` → default `"local"`
   (NONE mode) or admin user. Migration on first load
   (`if (ownerId == null) ownerId = "local"`).
2. **Backward compatible**: `executeRun(def, input)` (without user) delegates
   with `Principal.anonymous()` → no breaking change for existing callers.
3. **Feature flag**: `ENGINE_WORKFLOW_RBAC_ENABLED` (default `false`) for
   gradual activation. When `false`, behavior as before (global,
   anonymous).

## Test Strategy

| Level | Tests | Gate |
|---|---|---|
| Unit | Principal.system(), AgentStore ownerId, WorkflowDefinition round-trip | `mvn test` |
| Integration | Access-guard 403/200, ownership isolation, system token | `mvn test` (Quarkus @QuarkusTest) |
| E2E | OIDC login → create workflow → run → resume | manual (Keycloak/Auth0) |
| Coverage | JaCoCo ≥ 80 % | `mvn verify` |

## Code References (precise)

### Step 1 – `Principal.system()`
- **File:** `engine/src/main/java/de/augmentia/oc/engine/core/model/Principal.java`
- **Insertion point:** after `localAdmin()` (line 32), before `isAdmin()`.
- **Pattern:** analogous to `localAdmin()`, but `id="system"`, `roles=List.of("system")`.

### Step 2 – System-token auth
- **File:** `quarkus/src/main/java/de/augmentia/oc/engine/http/BasicAuthFilter.java`
- **Insertion point:** in `filter()`, line 39, after the `config.js` bypass, before `AuthMode mode = ...`.
- **New config:** `@ConfigProperty(name="engine.system.token") String systemToken;`
  and `@ConfigProperty(name="engine.system.roles", defaultValue="system") String systemRoles;`
  in `EngineConfig` or directly in `BasicAuthFilter`.
- **Logic:** `if (header != null && header.startsWith("X-System-Token") ...)` →
  compare with `systemToken`, on match `principalContext.set(new Principal("system", "system", roles, ...))`.

### Step 4 – `ownerId` in `WorkflowDefinition`
- **File:** `workflow-runner/.../model/WorkflowDefinition.java`
- **WorkflowDefinition record:** new field after `agents`: `String ownerId`.
  Compact constructor: `if (ownerId == null) ownerId = "";`
- **Agent record:** new fields after `responseSchema`:
  `String ownerId` and `List<String> rolesAllowed`.
  Compact constructor: `if (ownerId == null) ownerId = "";`,
  `rolesAllowed = rolesAllowed == null ? List.of("*") : List.copyOf(rolesAllowed);`
- **AgentStore.save()** (line 36–50): `withId` pattern in `AgentStoreResource` (line 100–107)
  must pass through `ownerId` and `rolesAllowed`.
- **AgentStoreResource.withId()** (line 100): extend with the new fields.

### Step 5 – Principal in WorkflowExecutor / DefaultNodeDispatcher
- **WorkflowExecutor.executeRun** (line 70): new overload
  `executeRun(def, input, String user)` and `executeRun(def, input, Principal principal)`.
- **Line 82:** `engine.createSession("workflow:" + workflowId, "workflow", "workspace")`
  → `engine.createSession("workflow:" + workflowId, user, "workspace")`.
- **DefaultNodeDispatcher.runAgent** (line 218): add `Principal principal` parameter;
  `engine.runPrompt(child, prefixedName, prompt, config, true, null, null, principal)`.
- **DefaultNodeDispatcher** constructor: `Principal` as field (optional, default anonymous).

### Step 6 – Guards in WorkflowResource
- **File:** `quarkus/.../http/WorkflowResource.java`
- **Insertion point:** after the `@Inject` fields (line 30–36): `@Inject SessionAccessGuard accessGuard;`
- **listWorkflows()** (line 40): filter analogous to `agentStore` by ownerId.
- **run()** (line 112): `@HeaderParam("X-QOC-User") String user` parameter.
- **executor()** (line 159): `new WorkflowExecutor(engine.qocEngine(), store, mcpManager, agentStore)` —
  already present; user propagation via `executeRun(def, input, user)`.

### Step 7 – Guards in AgentStoreResource
- **File:** `quarkus/.../http/AgentStoreResource.java`
- **Insertion point:** after `@Inject AgentStore store;` (line 37).
- **list()** (line 42): `store.listAll(userId, isAdmin)`.
- **AgentStore.listAll()** (line 61): overload `listAll(String userId, boolean isAdmin)`.

### Step 8 – Journal events with ownerId
- **File:** `engine/.../core/model/SessionEvent.java`
- **Search:** `WorkflowRunStarted`, `WorkflowNodeCompleted`, `WorkflowRunFinished` records.
- **JournalWorkflowEventSink** (line 45–50): extend `nodeCompleted(...)` with `ownerId`.

### Step 9 – @RolesAllowed
- **Files:** `WorkflowResource.java`, `AgentStoreResource.java`
- **Pattern:** `@RolesAllowed("user", "admin", "system", "viewer")` at class level
  or method level (analogous to `RolesAllowedFilter`).

## Dependencies

- `Principal.system()` must be added in `engine.core` (step 1).
- `SessionAccessGuard` is already `@RequestScoped` and injectable.
- `EngineConfig.AgentConfig` already has `rolesAllowed` — as a model for
  `WorkflowDefinition.Agent`.

## Risks

| Risk | Mitigation |
|---|---|
| Breaking change for existing workflows | Feature flag + default `"local"` |
| `@RolesAllowed` breaks NONE mode | In NONE mode: `Principal.localAdmin()` (admin role) → everything allowed |
| Performance (ownerId filter on list()) | In-memory filter (small data volume); DB index when needed |
| Test infrastructure (OIDC) | `quarkus-test-oidc-server` (already in pom.xml) |

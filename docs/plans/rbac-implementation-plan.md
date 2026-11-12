# RBAC + Workflow-OIDC – Detaillierter Umsetzungsplan

> Ablage: `/home/torsten/.opencode/plan/rbac-implementation-plan.md`
> Ziel-Ablage (Code-Modus): `qoc/docs/plans/rbac-workflow-oidc-implementation.md`
> Bezug: `qoc/docs/engineering/Workflow-OIDC.md`

## Geplante Sicherheitsfunktionen

Dieser Plan implementiert folgende Sicherheitsfunktionen für den Workflow-Runner,
die bisher fehlen oder nur teilweise vorhanden sind:

1. **Authentifizierung (AuthN)** – Wiederverwendung der QOC-Auth-Modi:
   - `none` (lokal, kein Login), `basic` (HTTP Basic), `oidc` (Keycloak/Auth0),
     `proxy` (Trusted-Proxy), `mcp_oauth`.
   - System-Token-Auth via `X-System-Token`-Header für Service-Accounts.
   - OIDC Principal-Extraktion aus JWT (Rollen, Groups, Claims).

2. **Autorisierung (AuthZ) – RBAC**:
   - Rollen: `admin`/`qoc-admin`, `user`, `system`, `viewer`/`reader`.
   - `@RolesAllowed`-Annotationen auf allen Workflow- und Agent-Endpoints.
   - `SessionAccessGuard` für Ownership-Prüfungen (nur Owner/Admin/System dürfen
     zugreifen).
   - Agent-Level-Zugriff via `rolesAllowed` (analog zu `EngineConfig.AgentConfig`).

3. **Ownership-Modell**:
   - `ownerId` auf `WorkflowDefinition` und `WorkflowDefinition.Agent`.
   - Runs, Workflows und Agents sind user-scoped (eigene + shared).
   - Systemuser (`Principal.system()`) für automatisch erstellte Ressourcen.

4. **Systemuser (Service-Account)**:
   - Synthetischer Principal mit Rolle `system`.
   - Für Scheduler, Webhooks, Background-Jobs ohne interaktiven Login.
   - Konfigurierbar: `ENGINE_SYSTEM_TOKEN`, `ENGINE_SYSTEM_ROLES`,
     `ENGINE_SYSTEM_OWNERSHIP`.

5. **OIDC-Integration für Workflows**:
   - Token-Propagation: UI → `/api/workflows/*` → `WorkflowExecutor` →
     `QocEngine.runPrompt` (Principal statt anonymous).
   - Role-Claim-Pfad pro Profil (Keycloak `realm_access/roles`, Auth0
     namespaced).
   - Trusted-Proxy-Auth (`X-Forwarded-*`).
   - Fail-closed bei fehlenden Claims (403).

6. **Audit & Traceability**:
   - `ownerId` in Journal-Events (`WorkflowRunStarted`, `WorkflowNodeCompleted`,
     `WorkflowRunFinished`).
   - Systemuser-Aktionen auditierbar über `principalId="system"`.
   - Optional: `WorkflowAgentChanged`-Event für Agent-Änderungen.

7. **Migration & Rückwärtskompatibilität**:
   - Feature-Flag `ENGINE_WORKFLOW_RBAC_ENABLED` (default `false`).
   - Bestehende Daten erhalten Default-`ownerId` (`"local"`).
   - `executeRun(def, input)` (ohne user) delegiert mit `Principal.anonymous()`
     → kein Breaking Change.

8. **Sicherheits-Stufen** (aus `06-security-hardening.md`):
   - Least-Privilege als Default (`engine.permission.default=least-privilege`).
   - Shell-Sandbox (bwrap, `workspace-write`).
   - Kurzlebige Tool-Tokens (60 s).
   - Container-Hardening (read-only FS, `cap_drop ALL`, Seccomp).

## Übersicht

12 Implementierungsschritte in 4 Phasen. Jeder Schritt hat: Ziel, Dateien,
Code-Änderungen, Tests, Commit. Tests müssen grün sein vor dem Commit.

---

## Phase 1: Foundation (Systemuser + Principal)

### Schritt 1: `Principal.system()` in engine.core

**Ziel:** Systemuser-Principal im Core verfügbar machen.

**Dateien:**
- `engine/src/main/java/de/augmentia/oc/engine/core/model/Principal.java`

**Änderung:**
```java
public static Principal system() {
    return new Principal("system", "system", List.of("system"), List.of(), Map.of(), null);
}
```

**Tests:**
- `PrincipalTest` (neu oder erweitern): `Principal.system().id() == "system"`, `roles().contains("system")`, `isAdmin() == false`.

**Commit:** `feat(engine): add Principal.system() for service-account principal`

---

### Schritt 2: System-Token-Auth in BasicAuthFilter

**Ziel:** `X-System-Token`-Header gegen `ENGINE_SYSTEM_TOKEN` validieren und
`Principal.system()` setzen.

**Dateien:**
- `quarkus/src/main/java/de/augmentia/oc/engine/http/BasicAuthFilter.java`
- `quarkus/src/main/resources/application.properties` (neue Keys)

**Änderung:**
- Neue Config: `engine.system.token`, `engine.system.roles`.
- In `filter()`: wenn Header `X-System-Token` vorhanden und mit
  `engine.system.token` übereinstimmt → `principalContext.set(new Principal("system", "system", systemRoles, ...))`.
- Vor der Basic-Auth-Prüfung einfügen.

**Tests:**
- `BasicAuthFilterTest` (erweitern): System-Token gültig → Principal.system();
  ungültig → 401; fehlend → Fallback auf Basic/OIDC.

**Commit:** `feat(security): system-token auth via X-System-Token header`

---

### Schritt 3: `@RolesAllowed` für `system`-Rolle

**Ziel:** `RolesAllowedFilter` erkennt `system` als gültige Rolle.

**Dateien:**
- `quarkus/src/main/java/de/augmentia/oc/engine/http/security/RolesAllowedFilter.java`

**Änderung:** Bereits generisch (prüft `userRoles.contains(req)`). Keine
Änderung nötig — `system` wird automatisch erkannt. Nur Dokumentation.

**Tests:** keine neuen.

---

## Phase 2: Ownership-Modell (Daten)

### Schritt 4: `ownerId` in `WorkflowDefinition` und `Agent`

**Ziel:** Ownership-Feld in den Modellen.

**Dateien:**
- `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/model/WorkflowDefinition.java`

**Änderung:**
- `WorkflowDefinition`-Record: neues Feld `String ownerId` (nullable, Default null).
- `WorkflowDefinition.Agent`-Record: neues Feld `String ownerId` (nullable) und
  `List<String> rolesAllowed` (Default `List.of("*")`).
- Compact Constructors: null-Handling.
- `WorkflowStore` (file-based): Round-trip (Jackson serialisiert automatisch).
- `AgentStore.save()`: ownerId/rolesAllowed durchreichen (wie responseSchema).

**Tests:**
- `WorkflowDefinitionTest` (falls vorhanden) oder neuer Test:
  Serialisierung/Deserialisierung mit ownerId und rolesAllowed.
- `AgentStoreTest` (neu): save/get mit ownerId.

**Commit:** `feat(workflow): add ownerId and rolesAllowed to WorkflowDefinition and Agent`

---

### Schritt 5: `Principal` in `WorkflowExecutor` und `DefaultNodeDispatcher`

**Ziel:** User/Principal durchreichen statt `anonymous()`.

**Dateien:**
- `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/WorkflowExecutor.java`
- `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/DefaultNodeDispatcher.java`

**Änderung:**
- `WorkflowExecutor`: neue Overload `executeRun(def, input, String user)` und
  `executeRun(def, input, Principal principal)`. Bestehende Methoden delegieren
  mit `Principal.anonymous()`.
- `executeRun`: `engine.createSession("workflow:" + workflowId, user, "workspace")`
  statt hardcoded `"workflow"`.
- `def.ownerId` setzen, falls null (aus user).
- `WorkflowExecutor.run(session, runId, ...)` → `ctx` oder `sink` mit ownerId.
- `DefaultNodeDispatcher`: `runAgent` nutzt `engine.runPrompt(child, ..., principal)`
  statt `Principal.anonymous()`. Principal als Feld oder Parameter.

**Tests:**
- `WorkflowExecutorTest` (erweitern): executeRun mit user → Session ownerId == user.
- `DefaultNodeDispatcherTest` (falls vorhanden): runAgent nutzt übergebenen Principal.

**Commit:** `feat(workflow): thread Principal/user through WorkflowExecutor and NodeDispatcher`

---

## Phase 3: Access Control (Guards)

### Schritt 6: Guards in `WorkflowResource`

**Ziel:** `SessionAccessGuard` auf Workflow-Endpoints anwenden.

**Dateien:**
- `quarkus/src/main/java/de/augmentia/oc/engine/http/WorkflowResource.java`

**Änderung:**
- `@Inject SessionAccessGuard accessGuard;`
- `listWorkflows()`: filtern nach ownerId (admin/system sehen alle).
- `getWorkflow(id)`: `accessGuard.requireAccess` (oder neuer `requireWorkflowAccess`).
- `saveWorkflow(id, def)`: owner/admin/system.
- `deleteWorkflow(id)`: owner/admin/system.
- `run(request)`: `@HeaderParam("X-QOC-User")`, `accessGuard.requireAgent` für eingebettete Agents, User-Durchreichung.
- `listRuns`, `runStatus`, `resumeRun`: ownerId-Filter / requireAccess.

**Neue Helper in `SessionAccessGuard`:**
- `requireWorkflowAccess(String workflowId, String ownerId)` → 403 wenn nicht owner/admin/system.
- `isWorkflowOwner(ownerId, current)` → boolean.

**Tests:**
- `WorkflowResourceTest` (neu oder erweitern): 403 für non-owner, 200 für owner/admin, Run-Filtering.

**Commit:** `feat(workflow): apply SessionAccessGuard to WorkflowResource endpoints`

---

### Schritt 7: Guards in `AgentStoreResource`

**Ziel:** Agent-Store endpoints absichern.

**Dateien:**
- `quarkus/src/main/java/de/augmentia/oc/engine/http/AgentStoreResource.java`

**Änderung:**
- `@Inject SessionAccessGuard accessGuard;`
- `list()`: ownerId-Filter (admin/system sehen alle).
- `get(id)`: requireAgentAccess.
- `create(body)`: owner = currentUser (oder system).
- `save(id, body)`: owner/admin/system.
- `delete(id)`: owner/admin/system.

**AgentStore-Änderung:**
- `AgentStore.listAll(userId, isAdmin)` Overload; bestehende `listAll()` delegiert mit `null, true` (backward-compat).

**Tests:**
- `AgentStoreResourceTest` (neu): 403/200, ownerId-Filter.

**Commit:** `feat(workflow): apply SessionAccessGuard to AgentStoreResource`

---

## Phase 4: Audit & Tests

### Schritt 8: Journal-Events mit ownerId

**Ziel:** `ownerId` in Workflow-Run-Events aufnehmen.

**Dateien:**
- `engine/src/main/java/de/augmentia/oc/engine/core/model/SessionEvent.java`
- `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/JournalWorkflowEventSink.java`

**Änderung:**
- `WorkflowRunStarted` / `WorkflowNodeCompleted` / `WorkflowRunFinished` um
  `ownerId`-Feld erweitern (nullable).
- `JournalWorkflowEventSink`: ownerId aus Session/Principal übergeben.

**Tests:**
- `WorkflowRunJournalTest` (erweitern): ownerId in Events.

**Commit:** `feat(workflow): include ownerId in workflow journal events`

---

### Schritt 9: `@RolesAllowed`-Annotationen

**Ziel:** JAX-RS Security-Annotationen auf Endpoints.

**Dateien:**
- `WorkflowResource.java`, `AgentStoreResource.java`

**Änderung:**
- `@RolesAllowed("user", "admin", "system", "viewer")` auf GET-Endpoints.
- `@RolesAllowed("user", "admin", "system")` auf POST/PUT/DELETE.

**Tests:**
- `RolesAllowedFilter`-Tests (falls vorhanden) erweitern.

**Commit:** `feat(workflow): add @RolesAllowed to workflow and agent endpoints`

---

### Schritt 10: System-Token-Tests

**Ziel:** Unit-Tests für System-Token-Auth.

**Dateien:**
- `quarkus/src/test/java/.../BasicAuthFilterTest.java` (erweitern)

**Tests:**
- Gültiger System-Token → `Principal.id() == "system"`, roles enthält `"system"`.
- Ungültiger Token → 401.
- Fehlender Token → Fallback auf Basic/OIDC/NONE.

**Commit:** `test(security): system-token auth tests`

---

### Schritt 11: Ownership-Tests

**Ziel:** Integrationstests für User-Isolation.

**Dateien:**
- `quarkus/src/test/java/.../WorkflowOwnershipTest.java` (neu)

**Tests:**
- User A erstellt Workflow → User B bekommt 403 auf get/save/delete/run.
- Admin sieht alle Workflows.
- System-User erstellt Workflow → ownerId = "system".
- Shared Workflow (ownerId=null) lesbar für alle authentifizierten User.

**Commit:** `test(workflow): ownership isolation integration tests`

---

### Schritt 12: Dokumentation

**Ziel:** Workflow-OIDC.md in engineering-Ordner (bereits erstellt), CONFIG.md ergänzen.

**Dateien:**
- `qoc/docs/CONFIG.md` (Systemuser-Keys ergänzen)
- `qoc/docs/engineering/Workflow-OIDC.md` (bereits erstellt)

**Tests:** keine.

**Commit:** `docs: add systemuser and RBAC config to CONFIG.md`

---

## Migrations-Strategie

1. **Bestehende Daten**: Workflows/Agents ohne `ownerId` → Default `"local"`
   (NONE-Modus) oder admin-User. Migration beim ersten Laden
   (`if (ownerId == null) ownerId = "local"`).
2. **Rückwärtskompatibel**: `executeRun(def, input)` (ohne user) delegiert
   mit `Principal.anonymous()` → kein Breaking Change für bestehende Aufrufer.
3. **Feature-Flag**: `ENGINE_WORKFLOW_RBAC_ENABLED` (default `false`) für
   schrittweise Aktivierung. Wenn `false`, Verhalten wie bisher (global,
   anonymous).

## Test-Strategie

| Stufe | Tests | Gate |
|---|---|---|
| Unit | Principal.system(), AgentStore ownerId, WorkflowDefinition Round-trip | `mvn test` |
| Integration | Access-Guard 403/200, Ownership-Isolation, System-Token | `mvn test` (Quarkus @QuarkusTest) |
| E2E | OIDC-Login → Workflow erstellen → Run → Resume | manuell (Keycloak/Auth0) |
| Coverage | JaCoCo ≥ 80 % | `mvn verify` |

## Code-Referenzen (präzise)

### Schritt 1 – `Principal.system()`
- **Datei:** `engine/src/main/java/de/augmentia/oc/engine/core/model/Principal.java`
- **Einfügepunkt:** nach `localAdmin()` (Zeile 32), vor `isAdmin()`.
- **Pattern:** analog zu `localAdmin()`, aber `id="system"`, `roles=List.of("system")`.

### Schritt 2 – System-Token-Auth
- **Datei:** `quarkus/src/main/java/de/augmentia/oc/engine/http/BasicAuthFilter.java`
- **Einfügepunkt:** in `filter()`, Zeile 39, nach dem `config.js`-Bypass, vor `AuthMode mode = ...`.
- **Neue Config:** `@ConfigProperty(name="engine.system.token") String systemToken;`
  und `@ConfigProperty(name="engine.system.roles", defaultValue="system") String systemRoles;`
  in `EngineConfig` oder direkt in `BasicAuthFilter`.
- **Logik:** `if (header != null && header.startsWith("X-System-Token") ...)` →
  vergleiche mit `systemToken`, bei Match `principalContext.set(new Principal("system", "system", roles, ...))`.

### Schritt 4 – `ownerId` in `WorkflowDefinition`
- **Datei:** `workflow-runner/.../model/WorkflowDefinition.java`
- **WorkflowDefinition-Record:** neues Feld nach `agents`: `String ownerId`.
  Compact Constructor: `if (ownerId == null) ownerId = "";`
- **Agent-Record:** neue Felder nach `responseSchema`:
  `String ownerId` und `List<String> rolesAllowed`.
  Compact Constructor: `if (ownerId == null) ownerId = "";`,
  `rolesAllowed = rolesAllowed == null ? List.of("*") : List.copyOf(rolesAllowed);`
- **AgentStore.save()** (Zeile 36–50): `withId`-Pattern in `AgentStoreResource` (Zeile 100–107)
  muss `ownerId` und `rolesAllowed` durchreichen.
- **AgentStoreResource.withId()** (Zeile 100): erweitern um die neuen Felder.

### Schritt 5 – Principal in WorkflowExecutor / DefaultNodeDispatcher
- **WorkflowExecutor.executeRun** (Zeile 70): neue Overload
  `executeRun(def, input, String user)` und `executeRun(def, input, Principal principal)`.
- **Zeile 82:** `engine.createSession("workflow:" + workflowId, "workflow", "workspace")`
  → `engine.createSession("workflow:" + workflowId, user, "workspace")`.
- **DefaultNodeDispatcher.runAgent** (Zeile 218): Parameter `Principal principal` hinzufügen;
  `engine.runPrompt(child, prefixedName, prompt, config, true, null, null, principal)`.
- **DefaultNodeDispatcher** Konstruktor: `Principal` als Feld (optional, Default anonymous).

### Schritt 6 – Guards in WorkflowResource
- **Datei:** `quarkus/.../http/WorkflowResource.java`
- **Einfügepunkt:** nach den `@Inject`-Feldern (Zeile 30–36): `@Inject SessionAccessGuard accessGuard;`
- **listWorkflows()** (Zeile 40): `agentStore`-ähnlicher Filter nach ownerId.
- **run()** (Zeile 112): `@HeaderParam("X-QOC-User") String user` Parameter.
- **executor()** (Zeile 159): `new WorkflowExecutor(engine.qocEngine(), store, mcpManager, agentStore)` —
  bereits vorhanden; User-Durchreichung über `executeRun(def, input, user)`.

### Schritt 7 – Guards in AgentStoreResource
- **Datei:** `quarkus/.../http/AgentStoreResource.java`
- **Einfügepunkt:** nach `@Inject AgentStore store;` (Zeile 37).
- **list()** (Zeile 42): `store.listAll(userId, isAdmin)`.
- **AgentStore.listAll()** (Zeile 61): Overload `listAll(String userId, boolean isAdmin)`.

### Schritt 8 – Journal-Events mit ownerId
- **Datei:** `engine/.../core/model/SessionEvent.java`
- **Suche:** `WorkflowRunStarted`, `WorkflowNodeCompleted`, `WorkflowRunFinished` Records.
- **JournalWorkflowEventSink** (Zeile 45–50): `nodeCompleted(...)` um `ownerId` erweitern.

### Schritt 9 – @RolesAllowed
- **Dateien:** `WorkflowResource.java`, `AgentStoreResource.java`
- **Pattern:** `@RolesAllowed("user", "admin", "system", "viewer")` auf Klassen-Ebene
  oder Methoden-Ebene (analog zu `RolesAllowedFilter`).

## Abhängigkeiten

- `Principal.system()` muss in `engine.core` ergänzt werden (Schritt 1).
- `SessionAccessGuard` ist bereits `@RequestScoped` und `@Inject`-fähig.
- `EngineConfig.AgentConfig` hat bereits `rolesAllowed` — als Vorbild für
  `WorkflowDefinition.Agent`.

## Risiken

| Risiko | Mitigation |
|---|---|
| Breaking Change für bestehende Workflows | Feature-Flag + Default `"local"` |
| `@RolesAllowed` bricht NONE-Modus | In NONE-Modus: `Principal.localAdmin()` (admin-Rolle) → alles erlaubt |
| Performance (ownerId-Filter auf list()) | In-Memory-Filter (kleine Datenmenge); DB-Index bei Bedarf |
| Test-Infrastruktur (OIDC) | `quarkus-test-oidc-server` (bereits in pom.xml) |

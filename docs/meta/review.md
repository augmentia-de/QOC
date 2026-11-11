# QOC Architektur- & Code-Review (`review.md`)

**Stand:** 2026-11-09  
**Basis:** Working Tree `qoc/` (HEAD `f631a7b` plus uncommittete Änderungen)  
**Umfang:** `qoc/engine`, `qoc/quarkus`, `qoc/cli`, `qoc/tui`, `qoc/deploy` (~597 Java-Dateien, ~55.700 LOC)

> **Verifikationshinweis:** Die ursprüngliche Fassung dieses Dokuments wurde gegen den Code geprüft. Korrigiert wurden insbesondere §3.5.4 (Subagent-Registry), §3.7.2 (Session-Isolation), §3.7.3 (Preflight) und §3.8 (Coverage/Gates). Die Handlungsempfehlungen in §4 sind detailliert mit Dateibezügen, Umsetzungsschritten, Tests und Akzeptanzkriterien. Zeilennummern beziehen sich auf den Working Tree vom 2026-11-09 und können sich nach Umbauten verschieben.

---

## 1. Executive Summary

QOC besitzt im Kern (`engine.core`, `engine.runtime`) eine **sehr saubere hexagonale Architektur**, die durch ArchUnit-Regeln (Gates G-1…G-9) mechanisch abgesichert ist. `engine.core` ist frei von Quarkus-, CDI-, Jackson- und HTTP-Abhängigkeiten.

Dem gegenüber steht eine **massiv angewachsene Adapter-/Outer-Skin-Schicht (`quarkus`)**, in der sich Orchestrierung, Persistenz-Nebenpfade, Security, Tool-Verdrahtung und UI-Logik in wenigen monolithischen Klassen konzentrieren. Zudem sind mehrere neuere Features (LangChain4j-Adapter, Triage-LLM, Teile des Resilience-Wrappers) nur als Skelett oder unverdrahtet vorhanden.

**Wichtigste Risiken in Kurzform:**

1. **Korrektheit:** PostgreSQL-spezifisches SQL in `Engine.java` lässt die Retention von Approvals und Tool-Cache im SQLite-Modus fehlschlagen (Fehler wird nur geloggt).
2. **Security:** Die Session-Isolation ist im HTTP-Layer nur teilweise umgesetzt; Messages, Questions und Permissions sind für fremde User erreichbar. Ein `owner_id` fehlt in der DB.
3. **Wartbarkeit:** `Engine.java` (3.443 Z.) und `HttpApi.java` (1.587 Z.) sind God-Classes; SQL- und SQLite-Adapter sind nahezu vollständig dupliziert.
4. **Nebenläufigkeit:** ThreadLocal-Brücken und Executor-Erzeugung pro Tool-Call/Step sind fragil bzw. ineffizient.
5. **Unfertige Features:** LangChain4j, `SessionWake`, LLM-Triage und Subagent-Deduplizierung sind Stubs oder nicht integriert.

---

## 2. Stärken (Was gut gelöst ist)

1. **Strikte Schichten-Trennung in `engine`:** Stufen S0 (`model`, `port`, `json`) → S1 (`contract`, `event`) → S2 (`eval`) → `runtime` → `adapter` sind zyklusfrei und per ArchUnit erzwungen.
2. **Explizite Domänenmodellierung:** Eigene Value-Typen (`SessionId`, `AgentId`, `CallId`, `TokenCount`, `Money`, `Option<T>`, `Result<T>`) statt primitiver Typen oder `null`-Sentinels.
3. **Mehrstufiges Permission-Modell:** Kombination aus Least-Privilege-Baseline, Agent-Frontmatter, Session-Rules, `AskMode` (`deny`/`allow`/`interactive`), Pfad-Allow-Listen (`FileAccess`) und `PortableScanner` für Shell-Ketten.
4. **Idempotenz & Nachvollziehbarkeit:** Append-only Event-Journal, `ToolResultCache` (Idempotency-Key), dedizierter rotierender LLM-Trace.
5. **Testbasis:** 763 Engine-Tests und 122 Quarkus-Tests laufen aktuell fehlerfrei (Stand 2026-11-08); die Kern-Domäne ist gut abgedeckt.

---

## 3. Architektonische Schwächen & Technische Schulden

### 3.1 God-Classes in `quarkus` (Kritisch)

| Datei | Zeilen | Problem |
|---|---:|---|
| `quarkus/.../http/Engine.java` | **3.443** | Vereint DI-Wiring, Session-Lifecycle, Prompt-Loop, Subagent-Orchestrierung, MCP-Discovery, A2A-Setup, Retention-Scheduler, Workspace-Scoping, File-API und direkte JDBC-Queries. |
| `quarkus/.../http/HttpApi.java` | **1.587** | Alle REST-/SSE-Endpunkte, DTO-Mapping, Auth-/Rollenprüfung, Datei-Up/Download und HTML-Rendering für E-Mail-Questions in einer Klasse. |
| `quarkus/.../http/EngineConfig.java` | **832** | Mischt MicroProfile-Config-Injection, JSON/JSONC-File-Merging, Markdown-Agent-Loading, Skill-Filter und Default-Agent-Erzeugung. |
| `quarkus/.../META-INF/resources/index.html` | **2.370** | Komplette Single-Page-UI (HTML + CSS + JS, SSE-Client, Markdown-Renderer, Modals) in einer einzigen statischen Datei ohne Build-/Test-Pipeline. |

**Auswirkung:** Hohe Änderungsrisiken, schwere Testbarkeit (Unit-Tests für `Engine` benötigen komplexe Setups oder Quarkus-Boot), Verstöße gegen Single-Responsibility.

### 3.2 Architektur-Bypass in `quarkus` (Direktes SQL am Port vorbei)

Während `engine` strikt prüft, dass `java.sql.*` nur in `adapter.sql` und `adapter.sqlite` vorkommt (`engine/src/test/.../arch/ArchitectureTest.java:61`), gilt diese ArchUnit-Regel **nicht** für das Modul `quarkus`. Die Quarkus-ArchUnit-Regeln prüfen nur `noImplSuffix` und `noStandardStreams` (`quarkus/src/test/.../arch/ArchitectureTest.java:18-23`).

Dadurch sind direkte SQL-Zugriffe in die HTTP-/Wiring-Schicht gewandert:

- `Engine.java` führt eigene `DELETE FROM pending_approval ...`, `DELETE FROM tool_result_cache ...`, `DELETE FROM tool_output ...` und `DELETE FROM snapshot ...` direkt über `dataSource.getConnection()` aus (`Engine.java:845,852,1092-1107`). Weitere direkte JDBC-Nutzung außerhalb der Adapter: Readiness-Check (`1540`) und Preflight-DB-Check (`1835`).
- **Dialekt-Bug:** `purgeDecidedApprovals()` und `purgeToolResultCache()` verwenden PostgreSQL-Syntax `now() - interval '...'` (`Engine.java:1095,1107`). Die SQLite-Migrationen definieren `pending_approval.created_at` als `integer` und `tool_result_cache.created_at` als `text` (`db/migration-sqlite/V3__pending_approvals.sql`, `V7__tool_result_cache.sql`). Im SQLite-Modus schlägt die Query fehl; die `SQLException` wird gefangen und nur als Warnung geloggt. Die Retention läuft dort faktisch nicht — die App stürzt nicht ab, aber Daten wachsen unbegrenzt.
- `DurableQuestionSource.java` und `DurableApprovalSource.java` enthalten komplettes eigenes JDBC-SQL mitten im Paket `de.augmentia.oc.engine.http` (7 bzw. 8 `getConnection()`-Stellen).
- `purgeAuditLog()` ist portabel (`?`-Parameter), bleibt aber ebenfalls ein Architektur-Bypass.

### 3.3 Code-Duplizierung zwischen `adapter.sql` und `adapter.sqlite`

Nahezu jede Store-Klasse existiert doppelt mit fast identischer Logik:

- `SqlSessions` (329 Z.) vs. `SqliteSessions` (331 Z.)
- `SqlJournal` vs. `SqliteJournal`, `SqlConversation` vs. `SqliteConversation`, `SqlApprovals` vs. `SqliteApprovals`, `SqlClaims` vs. `SqliteClaims`, `SqlToolResultCache` vs. `SqliteToolResultCache`, `SqlSnapshots` vs. `SqliteSnapshots`, `SqlCredentials` vs. `SqliteCredentials`, `SqlInstructions` vs. `SqliteInstructions`, `SqlToolOutput` vs. `SqliteToolOutput`.

Jede Schema- oder Interface-Änderung (wie zuletzt `setIdleOutcome`) muss manuell an zwei Stellen synchron nachgezogen werden.

### 3.4 Threading- & Concurrency-Schwachstellen

Java 21 ist gesetzt (inkl. Virtual Threads), wird aber kaum genutzt; stattdessen existieren problematische Thread-Muster:

1. **Executor-Erzeugung pro Tool-Call (`ToolRuntime.java:100`):**  
   Für **jeden einzelnen Tool-Aufruf** wird bei aktivem Step-Timeout ein neuer `Executors.newSingleThreadExecutor()` + Thread erzeugt und im `finally` wieder beendet (`exec.shutdownNow()`). Bei parallelen Tool-Calls in `Step.java` (`Executors.newFixedThreadPool` pro Step, `Step.java:132`, `shutdown()` im `finally`) entsteht unnötiger Thread-Churn — kein Leak, aber hohe Allokations- und Scheduling-Last.
2. **ThreadLocal-Kopplung zwischen Step und Provider (`Engine.java` / `HttpProviderAdapter.java`):**  
   `Engine.LLM_EVENT_SINK` und `Engine.LLM_CALL_INDEX` (`Engine.java:160-163`) werden pro Prompt gesetzt (`Engine.java:1801-1804`) und zusätzlich in die statischen ThreadLocals von `HttpProviderAdapter` kopiert (`EVENT_SINK`, `CALL_INDEX`, `LAST_RESPONSE_SUMMARY`, `HttpProviderAdapter.java:49-51`). Wechselt die Ausführung in einen Worker-Thread (Timeout-Wrapper, Subagent-Pools, Reactive-Pipelines), gehen Live-Events, Call-Indizes oder Correlation-IDs verloren. `CorrelationContext` nutzt ebenfalls statische ThreadLocals (`CorrelationContext.java:11-12`).
3. **Ungebremste Roh-Threads:**  
   `new Thread(...)` in `Engine.java:514` (`SessionWake`), `Engine.java:756` (Retention), `DurableQuestionSource.java:99` (`email-poller`) und `HttpApi.java:1048`.

### 3.5 Unvollständige / Tote Integrationen

1. **LangChain4j ungenutzt:**  
   `LangChain4jProviderAdapter` (453 Z.) und `Assistant.java` sind im Code und `quarkus-langchain4j-openai` im POM, aber `ProviderProducer` instanziiert ausschließlich `HttpProviderAdapter` oder den Scripted-Mock (`ProviderProducer.java:49-66`). Ein `engine.provider.type` existiert nicht.
2. **`SessionWake` ist ein Stub (`Engine.java:514-523`):**  
   Der Wake-Callback startet einen Thread, loggt `"SessionWake: processing background results..."`, ruft aber **kein** `runPrompt` und keinen Resume auf. Der Kommentar beschreibt die Absicht, der Code führt sie nicht aus.
3. **`NoOpTriageService`:**  
   Phase 3 des Resilience-Wrappers ist als Interface + NoOp-Stub verdrahtet (`Engine.java:739`), ruft aber noch kein echtes Analyse-LLM auf.
4. **Subagent-Deduplizierung fehlt:**  
   `SubagentSessionRegistry` / `InMemorySubagentSessionRegistry` sind **untracked**, aber im Working Tree bereits verdrahtet: Instanziierung `Engine.java:466-467`, `registerChild` `2368`, `children`/`descendants` `2475,2484`, `remove` `885`. Sie dienen dem Parent/Child-Tracking (Cascade-Cancel), **nicht** der Deduplizierung. Eine echte Dedup-Logik (gleicher Parent + Agent + Prompt) existiert nur als Prompt-Hinweis.
5. **GraalVM Native Image:**  
   Funktioniert nur mit Workaround (`-Dquarkus.arc.exclude-types=org.a2aproject.sdk.**`, `deploy/Dockerfile.native:8`), weil das A2A-SDK im Static-/Bean-Initializer einen Thread startet (`MainEventBusProcessor` in `a2a-java-sdk-server-common-1.4.0.Final.jar`).

### 3.6 Fehlerbehandlung & Stille Fehler (`catch (Exception ignored)`)

Im Produktionscode finden sich **47 Stellen** mit `catch (RuntimeException ignored)` oder `catch (Exception ignored)` (`engine/src/main/java` + `quarkus/src/main/java`). Darunter kritische Pfade:

- Workspace-Status-Updates: `Engine.java:1706-1707, 1998-1999, 2187, 2196`
- Idle-Outcome-Persistenz: `Engine.java:2165-2166`
- Empty-Session-Cleanup: `Engine.java:2180-2182`
- SSE-/Subagent-Event-Publishing (z. B. im uncommitteten `Engine`-Diff)

Schlägt dort eine DB-Transaktion fehl, wird der Fehler komplett verschluckt. Das ist besonders kritisch, weil diese Pfade den Session-Zustand und die UI-Sichtbarkeit bestimmen.

### 3.7 Security & Multi-Tenancy-Grenzen

1. **Shell-Scanner (`PortableScanner`) ist keine harte Security-Boundary:**  
   Er erkennt einfache Pfad-Tokens (`PortableScanner.looksLikePath`), expandiert aber keine Shell-Variablen (`$HOME`, `$(...)`, Backticks). `splitChains` ist laut Kommentar explizit nicht quote-aware. Da `bwrap` in unprivilegierten Docker-Containern deaktiviert werden muss (`deploy/docker-compose.yml:80`), verlässt sich der Schutz im Container allein auf Unix-User-Rechte und Mount-Namespaces.
2. **Session-Isolation ist nur teilweise umgesetzt:**  
   Die DB hat **kein** `owner_id` in der `session`-Tabelle. Der HTTP-Layer schützt einige Endpunkte über `isOwner()`/`requireSessionAccess()`:
   - Liste: `GET /api/session` filtert (`HttpApi.java:181`)
   - Einzelabruf: `GET /session/{id}` → 403 (`HttpApi.java:195`)
   - Löschen: `DELETE /session/{id}` → 403 (`HttpApi.java:160`)
   - `requireSessionAccess()` für `resume`, `prompt`, `title`, `revert`, `compact`, `fork`, `interrupt`, `running` (`HttpApi.java:241,268,370,426,447,552,574,586`)
   - Admin-Bypass: `HttpApi.java:1522` (`hasRole("admin")`)

   **Nicht geschützt** sind dagegen:
   - `GET /session/{id}/messages` und `/message` (`HttpApi.java:337-365`)
   - `GET /session/{id}/question` und `POST .../question/{questionId}/reply` (`391,406`)
   - `GET /session/{id}/processes`, `POST .../processes/{id}/kill`, `GET/POST .../tasks`, `.../tree`, `.../move` (`475-531`)
   - `GET /session/{id}/permission` und `POST .../permission/{requestId}/reply` (`608,620`)
   - `GET /api/questions` listet alle pending Questions ungefiltert (`1185`)

   Ein authentifizierter User kann damit fremde Konversationen lesen, fremde Fragen beantworten oder fremde Tool-Freigaben erteilen, wenn er die Session-ID kennt. Die `isOwner()`-Prüfung ist zudem pfadbasiert (Workspace-Verzeichnis) und kein DB-Fremdschlüssel.
3. **Synchroner Blocking-Check in `PreflightChecker`:**  
   `DefaultPreflightChecker` macht bei **jedem** `runPrompt()` einen synchronen DNS-Lookup + HTTP-`HEAD`-Request zum Provider-Host (`Engine.java:1829-1855`), Standard-Timeout 5 s, ohne Caching/TTL. 404/405 werden als erreichbar behandelt (`< 500`), lösen also keinen Fehler aus — der Overhead ist reine Latenz. Bei langsamen Gateways kann jeder Turn unnötig verzögert werden.

### 3.8 Testabdeckung & Build-Gates

- **Teststand:** 763 Engine-Tests (0 Failures, 4 skipped) und 122 Quarkus-Tests (0 Failures, 23 skipped) laut Surefire-Reports vom 2026-11-08.
- **Coverage:** Der aktuelle JaCoCo-Report weist `engine` 42,74 % (24.349/56.973 Instructions) und `quarkus` 10,74 % (3.681/34.287) aus. Die Artefakte sind jedoch **nicht aus einem Lauf**: `jacoco.exec` stammt von 2026-11-08 19:36, die Surefire-Reports von 21:58, der CSV-Report wurde am 2026-11-09 08:31 erzeugt. Die Zahlen sind daher nur eingeschränkt belastbar und müssen vor einer Commit-Entscheidung frisch gemessen werden.
- **Kein mechanisches 80-%-Gate:** In `engine/pom.xml` ist JaCoCo nur mit `prepare-agent` + `report` konfiguriert, ohne `check`-Goal. Die 80-%-Regel ist eine AGENTS.md-Commit-Regel, kein Build-Gate.
- **`PSQLException: This connection has been closed`:** In den aktuellen Surefire-Reports nicht enthalten (0 Treffer). Die Beobachtung ist damit nicht reproduzierbar belegt und könnte aus älteren Läufen stammen.
- **Retention in Tests:** Es gibt keine `%test`-Overrides für `engine.session.retention-days` oder `engine.audit.retention-days` (`application.properties` hat nur Test-Port, Revert-Snapshot und Logging). Da `startRetention()` bei `sessionRetentionDays > 0` sofort startet, laufen Hintergrund-Jobs auch in `@QuarkusTest`-Kontexten.

### 3.9 Weitere Auffälligkeiten

- **Dirty Working Tree:** 5 modifizierte Dateien (`Engine.java`, `HttpApi.java`, `ToolRuntime.java`, `TaskTool.java`, `index.html`) und 2 untracked Registry-Dateien. Der Review beschreibt damit einen beweglichen Stand; Zeilennummern können sich verschieben.
- **Duplizierter Kommentar:** `Engine.java:2168-2170` enthält dreimal denselben Satz („Generate title after the first successful prompt…“).
- **Falsch platzierter Javadoc:** Der Javadoc-Block vor `purgeDecidedApprovals()` (`Engine.java:1081-1089`) beschreibt `captureSnapshot`, nicht die Purge-Methode.
- **Retention-Defaults inkonsistent:** `engine.audit.retention-days` defaultet auf **2 Tage** (`application.properties:182`, `deploy/.env:104`), während `settings.md` im Secure-Beispiel **30 Tage** nennt. `SESSION_RETENTION_DAYS` ist 7 (Code) vs. 14 (`deploy/.env`).
- **`review.md` selbst ist untracked** — Doku-Änderung noch nicht committed.

---

## 4. Handlungsempfehlungen (detailliert)

Die Maßnahmen sind nach Priorität, Abhängigkeit und Risiko sortiert. Empfohlene Reihenfolge: **P1 zuerst vollständig abschließen**, dann P2 (Architektur/Security), dann P3. Jede Maßnahme enthält Ziel, betroffene Dateien, Umsetzung, Tests/Akzeptanz und Aufwand.

### 4.1 Priorität 1 — Korrektheit & Stabilität

#### H1 — SQL-Dialekt-Bug in den Retention-Jobs beheben

**Ziel:** Retention von decided Approvals und Tool-Result-Cache funktioniert in PostgreSQL **und** SQLite; kein direktes Persistenz-SQL mehr in `Engine.java` (Readiness-/Preflight-Checks ausgenommen).

**Befund:** `Engine.java:1090-1111` nutzt `now() - interval '7 days'` bzw. `'1 day'`. SQLite kennt weder `now()` noch `interval`; die Query schlägt fehl und wird nur geloggt. `ToolResultCache` hat bereits die Methoden `delete`/`deleteBySession`, aber keine Retention-Methode (`engine/core/port/store/ToolResultCache.java`). `ApprovalStore` verwaltet **saved_approval**, nicht `pending_approval` — die im ursprünglichen Review vorgeschlagene Erweiterung von `ApprovalStore` wäre daher falsch. Pending Approvals werden von `DurableApprovalSource` (implementiert `ApprovalSource`) verwaltet.

**Umsetzung:**
1. `ToolResultCache` um `int deleteCreatedBefore(Instant cutoff)` erweitern; in `SqlToolResultCache` und `SqliteToolResultCache` mit `PreparedStatement` + `Timestamp`/`setLong` implementieren (SQLite-Spalte ist `text`/`integer`; den im Adapter verwendeten Speichertyp beachten).
2. Neuen Port `PendingApprovalStore` in `engine/core/port/store/` einführen (oder `ApprovalSource` um eine Default-Methode `purgeDecidedBefore(Instant)` erweitern, falls der Umbau zu groß ist) und `DurableApprovalSource` darauf umstellen. Methoden: `deleteDecidedBefore(Instant cutoff)`.
3. `Engine.purgeDecidedApprovals()` und `Engine.purgeToolResultCache()` durch Port-Aufrufe ersetzen; direkte SQL-Blöcke entfernen.
4. `purgeAuditLog()` ebenfalls über Ports (`Journal.deleteOlderThan`, neue Methoden für `tool_output`/`snapshot`) führen oder als befristete Ausnahme dokumentieren.
5. Migrationen prüfen: `pending_approval.created_at` ist in SQLite `integer`, in PG `timestamptz`; die Cutoff-Parameterisierung muss beide Typen korrekt behandeln.

**Tests/Akzeptanz:**
- Unit-Tests für `SqlToolResultCache`/`SqliteToolResultCache` mit festem `Instant`: Einträge älter als Cutoff werden gelöscht, jüngere bleiben.
- Integrationstest im SQLite-Profil: `purgeDecidedApprovals` und `purgeToolResultCache` laufen ohne Warnung und löschen die erwarteten Zeilen.
- `grep -R "now() - interval" quarkus/src/main/java` liefert keinen Treffer mehr.
- ArchUnit-Regel `javaSqlOnlyInSqlAdapter` wird auf das Quarkus-Modul ausgedehnt (siehe H8) und bleibt grün.

**Betroffene Dateien:** `engine/core/port/store/ToolResultCache.java`, `engine/adapter/sql/SqlToolResultCache.java`, `engine/adapter/sqlite/SqliteToolResultCache.java`, `quarkus/.../http/DurableApprovalSource.java`, `quarkus/.../http/Engine.java`, ggf. neue Port-/Adapter-Klassen.

**Aufwand:** 0,5–1 Tag · **Risiko:** niedrig

---

#### H2 — Preflight-Check cachen und konfigurierbar machen

**Ziel:** Kein synchroner DNS-/HTTP-Check bei jedem Turn; Preflight bleibt als Schutz erhalten, verursacht aber nur noch einmal pro TTL Latenz.

**Befund:** `DefaultPreflightChecker.check()` führt DNS-Lookup und HTTP-`HEAD` mit 5-s-Timeout aus; `Engine.runPrompt()` ruft ihn bei jedem Turn auf (`Engine.java:1829`). Es gibt keine Konfiguration für TTL oder Abschaltbarkeit des HTTP-Checks.

**Umsetzung:**
1. Neue Properties: `engine.preflight.ttl-seconds` (Default 60), `engine.preflight.http-check.enabled` (Default true), `engine.preflight.timeout-seconds` (Default 5).
2. In `DefaultPreflightChecker` einen kleinen Cache pro Provider-URL (`ConcurrentHashMap<String, CachedResult>` mit Zeitstempel) einführen; Ergebnis für die TTL wiederverwenden.
3. DNS- und HTTP-Check getrennt konfigurierbar machen (`engine.preflight.dns.enabled`, `engine.preflight.http-check.enabled`).
4. Bei `workspace`/`database`-Checks prüfen, ob sie ebenfalls gecacht werden können (Datenbank-Health ist günstig, Workspace-Writability ebenfalls).
5. Metriken ergänzen: `qoc_preflight_cache_hits_total`, `qoc_preflight_cache_misses_total`, `qoc_preflight_duration_seconds`.
6. Config-Doku in `docs/settings.md` und `deploy/.env.example` ergänzen.

**Tests/Akzeptanz:**
- Test mit Fake-`HttpClient`: erster Check ruft DNS/HTTP auf, zweiter Check innerhalb der TTL nicht; nach TTL-Ablauf wieder.
- Test: `http-check.enabled=false` überspringt den HTTP-Call, DNS/Workspace bleiben aktiv.
- Test: HTTP 404/405 werden als erreichbar behandelt (bereits implementiert, Regression absichern).
- Akzeptanz: `runPrompt`-Overhead bei Cache-Hit < 5 ms; keine Preflight-Warnung bei normalem Betrieb.

**Betroffene Dateien:** `engine/runtime/resilience/DefaultPreflightChecker.java`, `quarkus/.../http/Engine.java`, `quarkus/src/main/resources/application.properties`, `deploy/.env.example`, `docs/settings.md`.

**Aufwand:** 0,5–1 Tag · **Risiko:** niedrig

---

#### H3 — Executors in `ToolRuntime` und `Step` vereinheitlichen

**Ziel:** Kein Executor-Churn pro Tool-Call/Step; Timeout-Verhalten bleibt unverändert.

**Befund:** `ToolRuntime.java:100` erzeugt pro Tool-Aufruf einen `newSingleThreadExecutor` und beendet ihn im `finally`; `Step.java:132` erzeugt pro Step einen `newFixedThreadPool(MAX_PARALLEL_TOOLS, …)` und beendet ihn im `finally`. Beide Muster sind funktional korrekt (kein Leak), aber ineffizient.

**Umsetzung:**
1. In `ToolRuntime` und `Step` einen gemeinsamen, langlebigen `ExecutorService` injizieren (Konstruktor/Factory) oder einen `Executors.newVirtualThreadPerTaskExecutor()` verwenden, der über die Engine-Lebensdauer existiert und beim Shutdown geschlossen wird.
2. Falls kein gemeinsamer Executor eingeführt werden soll: mindestens `Thread.ofVirtual().factory()` bzw. `newVirtualThreadPerTaskExecutor()` statt Plattform-Thread-Pools verwenden.
3. Timeout-Semantik beibehalten: `Future.get(stepTimeoutSeconds, SECONDS)` + `cancel(true)`; bei Virtual Threads prüfen, ob die Tool-Implementierung `InterruptedException` korrekt behandelt.
4. Lifecycle: Executor im `Engine`-Shutdown (`@PreDestroy`) sauber beenden; kein `shutdownNow()` mitten in laufenden Tool-Calls.
5. Optional: Semaphore für parallele Tool-Calls beibehalten (`MAX_PARALLEL_TOOLS`), um Ressourcen zu begrenzen.

**Tests/Akzeptanz:**
- Unit-Test: Timeout eines Tools führt weiterhin zu `ReadTool.failure(... "step timeout")` und interrupted den Task.
- Lasttest/Integrationstest mit 10 parallelen Tool-Calls: keine Thread-Explosion, keine verlorenen Ergebnisse.
- Akzeptanz: `grep -R "newSingleThreadExecutor" engine/src/main/java` liefert keinen Treffer mehr (oder nur an einer zentralen Stelle).

**Betroffene Dateien:** `engine/runtime/tool/runtime/ToolRuntime.java`, `engine/runtime/step/Step.java`, ggf. `quarkus/.../http/Engine.java` (Wiring/Shutdown).

**Aufwand:** 0,5–1 Tag · **Risiko:** mittel (Timeout-/Interrupt-Semantik)

---

#### H4 — Retention-Jobs in Tests deaktivieren

**Ziel:** Deterministische `@QuarkusTest`-Läufe ohne Hintergrund-Timer, die DB-Verbindungen schließen oder Zustand verändern.

**Befund:** `startRetention()` startet bei `sessionRetentionDays > 0` sofort mehrere `scheduleWithFixedDelay`-Jobs (`Engine.java:753-771`). Es gibt keine `%test`-Overrides; die Jobs laufen daher auch in Tests. Da alle Purge-Jobs am selben Executor hängen, deaktiviert `session.retention-days=0` auch Approval-/Cache-Purges — das ist ein Kopplungseffekt, der bewusst dokumentiert werden sollte.

**Umsetzung:**
1. In `application.properties` ergänzen:
   - `%test.engine.session.retention-days=0`
   - `%test.engine.audit.retention-days=0`
2. Optional: `engine.retention.enabled` als eigenes Flag einführen und die Purge-Jobs unabhängig von `sessionRetentionDays` schaltbar machen (entkoppelt H1/H4).
3. In Tests, die Retention explizit prüfen, die Jobs gezielt über die Service-API aufrufen statt über den Scheduler.

**Tests/Akzeptanz:**
- `@QuarkusTest`-Suite läuft ohne `purge*`-Logzeilen und ohne DB-Connection-Fehler.
- Ein dedizierter Retention-Test prüft die Purge-Methoden direkt (ohne Scheduler).

**Betroffene Dateien:** `quarkus/src/main/resources/application.properties`, `quarkus/.../http/Engine.java`.

**Aufwand:** 0,25 Tag · **Risiko:** niedrig

---

#### H5 — Kritische `catch (ignored)`-Pfade sichtbar machen

**Ziel:** Fehler in Session-Zustand, Idle-Outcome und Event-Publishing werden mindestens geloggt, nicht verschluckt.

**Befund:** 47 stille Catch-Blöcke; besonders kritisch sind `Engine.java:1706-1707, 1998-1999, 2165-2166, 2180-2182, 2187, 2196`. Ein DB-Fehler beim Setzen des Idle-Outcomes oder beim Empty-Session-Cleanup bleibt unsichtbar.

**Umsetzung:**
1. In den genannten Pfaden `catch (RuntimeException e)` verwenden und `LOG.log(Level.FINE, "...", e)` bzw. bei kritischen Zustandsänderungen `LOG.warn(...)` loggen.
2. Für rein optionale UI-Events (SSE-Publish) `FINE` genügt; für Persistenz-Updates `WARNING`.
3. Das vorhandene Skript `check-no-catch-exception.sh` in die CI aufnehmen (oder als Maven-Exec-Phase) und als ArchUnit-Regel ergänzen: keine `catch`-Blöcke mit leerem Body in `quarkus`/`engine`.
4. Prüfen, ob `catch (RuntimeException ignored)` an manchen Stellen bewusst als Best-Effort dient; diese mit Kommentar begründen.

**Tests/Akzeptanz:**
- `grep -R "catch (.* ignored)"` auf kritische Pakete liefert keine Treffer mehr oder jeder Treffer hat einen begründenden Kommentar.
- Log-Test: Bei simuliertem DB-Fehler erscheint eine Warnung, der Turn bricht nicht ab.

**Betroffene Dateien:** `quarkus/.../http/Engine.java`, `quarkus/.../http/HttpApi.java`, `engine/.../runtime/**`, `check-no-catch-exception.sh`.

**Aufwand:** 1 Tag · **Risiko:** niedrig

---

### 4.2 Priorität 2 — Architektur & Security

#### H6 — `Engine.java` in fokussierte Services zerlegen

**Ziel:** `Engine` wird zum dünnen Wiring-/Fassaden-Layer (< 800 Zeilen); Fachlogik wandert in testbare Services.

**Befund:** `Engine.java` (3.443 Z.) vereint mindestens zehn Zuständigkeiten. Bereits vorhandene Bausteine (`SessionService`, `SessionRetention`, `PermissionEngine`, `SubagentTool`) können als Vorbild dienen.

**Umsetzung (Vorschlag):**
1. `PromptExecutionService` — `runPrompt`-Loop, Preflight, Retry, Triage, Compaction, Budgets, Idle-Outcome. Abhängigkeiten: Provider, `Step`, `ToolRuntime`, `TurnPolicy`, `SessionStore`.
2. `WorkspaceManager` — `user-ws`/Ephemeral-Workspaces, `allowedBaseFor`, File-API (`listFiles`, `readFile`, `writeFile`), Snapshot-Handling.
3. `RetentionService` — `purgeExpiredSessions`, `purgeAuditLog`, `purgeDecidedApprovals`, `purgeToolResultCache`, `sweepEphemeralOrphans`, `sweepPausedSessions`; eigener Executor (siehe H4).
4. `SubagentOrchestrator` — Depth-Tracking, Slot-Semaphore, `SubagentSessionRegistry`, BackgroundTasks, `SessionWake`.
5. `McpRegistry`/`A2AService` — Discovery/Setup aus `Engine` herausziehen.
6. `SessionFacade` — öffentliche API für `HttpApi`; delegiert an die Services.

**Vorgehen:** Strangler-Pattern: zuerst reine Extraktion ohne Verhaltensänderung, dann Tests pro Service mit Fakes ergänzen. Kein Big-Bang.

**Tests/Akzeptanz:**
- `Engine.java` < 800 Zeilen; jede neue Klasse < 500 Zeilen.
- Bestehende 763 Engine-Tests + Quarkus-Tests bleiben grün.
- Neue Unit-Tests für `RetentionService` und `WorkspaceManager` ohne Quarkus-Boot.
- Keine Zyklen zwischen neuen Services (ArchUnit).

**Betroffene Dateien:** `quarkus/.../http/Engine.java` und neue Service-Klassen im selben Paket (oder `quarkus/.../http/service/`).

**Aufwand:** 3–5 Tage · **Risiko:** mittel (Verhaltensrisiko durch große Extraktion)

---

#### H7 — `HttpApi.java` nach Ressourcen aufteilen

**Ziel:** REST-/SSE-Endpunkte werden pro Domäne getrennt, Auth-Checks zentralisiert und testbar.

**Befund:** `HttpApi.java` (1.587 Z.) enthält alle Endpunkte, DTO-Mapping, HTML-Rendering und Auth-Prüfungen. `requireSessionAccess` wird nur an 8 von ~25 Session-Endpunkten verwendet (siehe §3.7.2).

**Umsetzung (Vorschlag):**
1. `SessionResource` — CRUD, Liste, Resume, Prompt, Command, Messages, Title, Todo, Tree, Move, Fork, Interrupt, Running.
2. `QuestionResource` — pending Question, Reply, `/api/questions`, E-Mail-HTML.
3. `PermissionResource` — pending Permissions, Reply.
4. `WorkspaceFileResource` — `/files`, `/file`, `/file/download`, `/dir/download`.
5. `AgentResource` / `McpResource` / `AdminResource` — Agenten, MCP, Config, Health, Metrics, Audit.
6. Gemeinsame Helper: `SessionAccessGuard` (einheitlich `requireSessionAccess`), `PrincipalContext`, DTO-Mapper.
7. **Alle** Session-Endpunkte mit `SessionAccessGuard` absichern (siehe H10).

**Tests/Akzeptanz:**
- `HttpApi.java` < 300 Zeilen; jede Resource < 400 Zeilen.
- RestAssured-Tests pro Resource; insbesondere 403-Tests für fremde Sessions.
- OpenAPI-/Endpoint-Liste bleibt unverändert (Regressionstest).

**Betroffene Dateien:** `quarkus/.../http/HttpApi.java`, neue Resource-Klassen.

**Aufwand:** 2–3 Tage · **Risiko:** mittel (viele Endpunkte, Auth-Verhalten)

---

#### H8 — Persistenz aus `quarkus` in die Adapter-Schicht verschieben

**Ziel:** `DurableQuestionSource` und `DurableApprovalSource` enthalten kein JDBC mehr; die Quarkus-Schicht nutzt Ports. ArchUnit erzwingt die Regel auch im Quarkus-Modul.

**Befund:** Beide Klassen liegen in `de.augmentia.oc.engine.http` und enthalten direktes SQL. Eine Verschiebung in `engine.adapter.*` ist nicht 1:1 möglich, weil sie CDI-Annotationen (`@ApplicationScoped`, `@Inject`) und MicroProfile-Config nutzen und das Engine-Modul per ArchUnit frei von `jakarta.*` bleiben muss.

**Umsetzung:**
1. Neue Ports definieren:
   - `PendingApprovalStore` (create/find/resolve/deleteDecidedBefore)
   - `QuestionStore` (createPending/listPending/findByToken/resolve)
2. Reine JDBC-Implementierungen in `engine/adapter/sql/` und `engine/adapter/sqlite/` (ohne CDI).
3. `DurableApprovalSource`/`DurableQuestionSource` bleiben als dünne Quarkus-Services in `quarkus/.../http`, injizieren die Ports und enthalten nur noch Orchestrierung (Token, Notification, Polling).
4. ArchUnit-Regel im Quarkus-Modul ergänzen: `noClasses().that().resideOutsideOfPackages("..adapter..")...should().accessClassesThat().resideInAPackage("java.sql..")` — nach dem Umbau muss sie grün sein.
5. Alternativ, falls der Umbau zu groß ist: SQL in dedizierte `*SqlSupport`-Klassen im Engine-Adapter auslagern und die Quarkus-Klassen nur noch delegieren.

**Tests/Akzeptanz:**
- `grep -R "java.sql" quarkus/src/main/java` liefert keine Treffer mehr außerhalb klar dokumentierter Wiring-/Health-Check-Ausnahmen (Readiness/Preflight).
- Adapter-Tests für beide Dialekte (PG/SQLite) mit Token-Resolution und Purge.
- Quarkus-ArchUnit-Test läuft in `mvn test`.

**Betroffene Dateien:** `quarkus/.../http/DurableApprovalSource.java`, `DurableQuestionSource.java`, `engine/core/port/store/*`, `engine/adapter/sql/*`, `engine/adapter/sqlite/*`, `quarkus/src/test/.../arch/ArchitectureTest.java`.

**Aufwand:** 1–2 Tage · **Risiko:** mittel (CDI-/Config-Grenzen)

---

#### H9 — SQL-/SQLite-Duplizierung reduzieren

**Ziel:** Gemeinsame JDBC-Basisklassen + schmales Dialekt-Interface; ~1.500 LOC Duplikate entfallen.

**Befund:** 11 Store-Paare mit nahezu identischer Logik (siehe §3.3). Unterschiede betreffen im Wesentlichen Typen (`jsonb` vs. `text`), `ON CONFLICT`-Syntax (beide unterstützen es), Zeitstempel (`timestamptz` vs. `integer`/`text`) und `now()` vs. `datetime('now')`.

**Umsetzung:**
1. `SqlDialect`-Interface in `engine/adapter/sql/` mit Methoden wie `jsonType()`, `timestampType()`, `nowExpression()`, `bindInstant(PreparedStatement, int, Instant)`, `readInstant(ResultSet, String)`.
2. Abstrakte Basisklassen `AbstractJdbcSessions`, `AbstractJdbcJournal`, `AbstractJdbcApprovals`, `AbstractJdbcToolResultCache` etc.; die SQL-Strings werden einmal gepflegt, Dialektunterschiede über das Interface gekapselt.
3. `Sql*`- und `Sqlite*`-Klassen werden dünne Subklassen mit Dialekt-Instanz.
4. Migrationsparität als Test: beide Schemata müssen dieselben Tabellen/Spalten liefern (PG vs. SQLite).
5. Schrittweise vorgehen: mit `ToolResultCache` und `Approvals` beginnen, dann `Sessions`/`Journal`.

**Tests/Akzeptanz:**
- Bestehende `SqlAdaptersTest` und `SqliteAdaptersTest` bleiben grün.
- Neue parametrisierte Tests laufen gegen beide Dialekte.
- LOC-Reduktion messbar (Ziel: −1.000 bis −1.500 Zeilen).
- Keine Verhaltensänderung in Fehlerfällen (SQLException → `StoreFailure`).

**Betroffene Dateien:** `engine/adapter/sql/*`, `engine/adapter/sqlite/*`, neue `SqlDialect`/`AbstractJdbc*`-Klassen.

**Aufwand:** 2–3 Tage · **Risiko:** mittel (viele Stores, Schemaunterschiede)

---

#### H10 — Session-Isolation pro User (OIDC) vervollständigen

**Ziel:** Kein User kann fremde Sessions, Messages, Questions oder Permissions lesen/ändern; Admins behalten globalen Zugriff.

**Befund:** DB hat kein `owner_id`; HTTP schützt nur einen Teil der Endpunkte (siehe §3.7.2). `isOwner()` ist pfadbasiert und damit abhängig von korrekten Workspace-Verzeichnissen.

**Umsetzung:**
1. Migration `V9__session_owner.sql` (PG + SQLite): `alter table session add column owner_id text;` plus Index `(owner_id, time_updated)`.
2. Backfill: bestehende Sessions aus dem Workspace-Pfad ableiten (`user-ws/{id}` → `owner_id=id`), sonst `legacy`.
3. `SessionStore.create`/`SessionInfo` um `ownerId` erweitern; `createSession` schreibt `principal.id()`.
4. `SessionStore.list(ownerId)` bzw. Service-Filter; `Engine.listSessions(user, isAdmin)` liefert für Nicht-Admins nur eigene Sessions.
5. `SessionAccessGuard` an **allen** Session-Endpunkten verwenden: Messages, Question, Question-Reply, Processes, Tasks, Tree, Move, Permission, Permission-Reply. Bei fehlender Session 404, bei fremder Session 403 (kein Informationsleck).
6. `/api/questions` nach Owner filtern; `pendingQuestion`/`pendingPermissions` ebenfalls.
7. Admin-Bypass explizit über `hasRole("admin")` (wie heute in `HttpApi.isOwner`).
8. Audit: Zugriffe auf fremde Sessions als Warnung loggen (ohne Inhalte).

**Tests/Akzeptanz:**
- Zwei-User-Test: User B erhält 403/404 für alle Endpunkte von User A (Liste, Get, Messages, Question, Permission, Processes, Tasks, Tree, Move).
- Admin-Test: Admin sieht und bedient alle Sessions.
- Migrationstest: bestehende Sessions werden korrekt zugeordnet.
- Kein Endpunkt ohne Guard in der neuen Resource-Struktur (H7).

**Betroffene Dateien:** `engine/core/port/store/SessionStore.java`, `engine/adapter/sql/SqlSessions.java`, `engine/adapter/sqlite/SqliteSessions.java`, `quarkus/.../http/Engine.java`, `HttpApi.java`/neue Resources, Migrationen.

**Aufwand:** 2–4 Tage · **Risiko:** hoch (Schema-Migration, Auth-Verhalten, Rückwärtskompatibilität)

---

#### H11 — ThreadLocal-Brücken durch explizite Kontexte ersetzen

**Ziel:** Live-Events, Call-Index und Correlation-IDs bleiben auch bei Thread-Wechseln (Virtual Threads, Subagents, Timeouts) korrekt.

**Befund:** `Engine.LLM_EVENT_SINK`/`LLM_CALL_INDEX` werden in `HttpProviderAdapter.EVENT_SINK`/`CALL_INDEX` kopiert; `LAST_RESPONSE_SUMMARY` und `CorrelationContext` nutzen ebenfalls ThreadLocals. Bei Worker-Thread-Wechsel gehen Werte verloren oder werden falsch zugeordnet.

**Umsetzung:**
1. `ProviderAdapter`-API erweitern: `ModelRequest` (oder ein neues `ProviderCallContext`) trägt `Consumer<ModelEvent> eventSink`, `int callIndex`, `String correlationId`, `String sessionId`.
2. `HttpProviderAdapter` liest diese Werte aus dem Request-Kontext statt aus statischen ThreadLocals; `LAST_RESPONSE_SUMMARY` als Rückgabewert/`ModelResponse`-Feld führen.
3. `Engine` setzt den Kontext beim Aufruf; kein `ThreadLocal.set` mehr.
4. `CorrelationContext` an den Thread-Grenzen explizit propagieren (Executor-Wrapper, `MDC`-Copy) oder durch ein `RequestContext`-Objekt ersetzen.
5. SSE-Publishing aus dem Provider in den Kontext-Handler verlagern; bei Timeout/Interrupt sauber schließen.

**Tests/Akzeptanz:**
- Parallele Prompts in zwei Sessions: Events landen ausschließlich in der richtigen Session; Call-Indizes sind korrekt.
- Subagent in eigenem Thread: Correlation-ID und Session-ID bleiben gesetzt.
- Timeout-Test: nach `future.cancel` keine verwaisten Events.
- `grep -R "static final ThreadLocal" engine/src/main/java quarkus/src/main/java` liefert nur noch begründete Ausnahmen (z. B. `CorrelationContext` als Boundary).

**Betroffene Dateien:** `engine/runtime/provider/client/HttpProviderAdapter.java`, `engine/core/contract/provider/*`, `engine/runtime/step/Step.java`, `engine/runtime/observability/CorrelationContext.java`, `quarkus/.../http/Engine.java`.

**Aufwand:** 2–3 Tage · **Risiko:** mittel (Streaming-/Event-Verhalten)

---

#### H12 — Unfertige Features entscheiden und abschließen

**Ziel:** Kein Skelett-Code im Produktionspfad; klare Entscheidung pro Feature.

**Befund:** LangChain4j-Adapter, `SessionWake`, `NoOpTriageService`, Subagent-Dedup.

**Umsetzung (Entscheidungsmatrix):**

| Feature | Option A: integrieren | Option B: entfernen | Empfehlung |
|---|---|---|---|
| LangChain4j | `engine.provider.type=langchain4j` in `ProviderProducer`, Tool-/Streaming-Mapping testen, Integrationstest | Adapter + `Assistant` + Dependency entfernen | **A**, wenn Multi-Provider/Tool-Calling strategisch gewünscht; sonst **B** |
| `SessionWake` | Synthetischen Resume-Prompt bei idle Session und pending Background-Results ausführen; Lock gegen Doppel-Runs | Callback entfernen, explizites Polling dokumentieren | **A**, wenn Background-Subagents produktiv genutzt werden; sonst **B** |
| Triage | `LlmTriageService` mit kleinem Modell, Timeout, Fallback auf NoOp, Metriken | NoOp behalten und Feature-Flag dokumentieren | **A** nur mit klarem Betreibernutzen; sonst **B** |
| Subagent-Dedup | Key `(parent, agent, promptHash, TTL)` im `InMemorySubagentSessionRegistry`; `TaskTool` wiederverwendet laufenden Child | Prompt-Hinweis behalten, Registry nur für Tracking | **A**, wenn doppelte Subagents beobachtet werden |

**Tests/Akzeptanz:**
- Für jede Option: entweder Integrationstest + Config-Doku oder Entfernen inkl. POM-/Import-Bereinigung.
- Keine ungenutzte Klasse im `quarkus`-Modul ohne `@Deprecated`/Doku.
- `mvn dependency:analyze` meldet keine ungenutzte LangChain4j-Dependency mehr.

**Betroffene Dateien:** `quarkus/.../http/ProviderProducer.java`, `LangChain4jProviderAdapter.java`, `quarkus/.../quarkus/Assistant.java`, `quarkus/pom.xml`; `Engine.java` (SessionWake), `engine/runtime/resilience/*`, `engine/runtime/tool/subagent/*`.

**Aufwand:** 2–4 Tage (je nach Option) · **Risiko:** mittel

---

### 4.3 Priorität 3 — Qualität, UI & Dokumentation

#### H13 — Coverage-Ziel belastbar machen und Gate einführen

**Ziel:** Reproduzierbare Coverage-Messung; `mvn verify` schlägt unter 80 % (Instructions) für `engine` fehl.

**Befund:** Aktueller Report 42,74 % (engine) / 10,74 % (quarkus), aber Artefakte aus unterschiedlichen Läufen; kein `check`-Goal im POM; viele Pakete ohne Tests (`runtime.resilience` 0 %, `runtime.observability` 0 %, `runtime.step` 15,4 %, `adapter.sql` 3,3 %, `adapter.sqlite` 4,6 %).

**Umsetzung:**
1. JaCoCo `check`-Goal in `engine/pom.xml` ergänzen: `COVEREDRATIO` ≥ 0.80 für Instructions, `haltOnFailure=true`.
2. Sauberen Messlauf definieren: `mvn clean test` (keine `-DskipTests`, keine IDE-Runs); Report-Artefakte versionieren/zeitstempeln.
3. Testlücken schließen, priorisiert:
   - `DefaultPreflightChecker`, `DefaultErrorClassifier`, `RetryPolicy`, `LoopDetector`
   - `CorrelationContext`, `ResilienceMetrics`
   - `ToolRuntime`-Timeout, `Step`-Parallelität
   - `Sql*`/`Sqlite*`-Adapter (Retention, Tokens, Idle-Outcome)
4. Quarkus-Integrationstests stabilisieren: Retention in Tests aus (H4), Seriell-Ausführung für DB-Tests oder Testcontainers mit eigener DB pro Klasse.
5. Optional: Coverage-Badge/Report in CI veröffentlichen.

**Tests/Akzeptanz:**
- `mvn verify` in `qoc/` ist grün und erzwingt 80 % für `engine`.
- Frischer Report weist ≥ 80 % aus; die Zahl ist aus einem einzigen Lauf reproduzierbar.
- Keine Flakes in 3 aufeinanderfolgenden Läufen.

**Betroffene Dateien:** `engine/pom.xml`, `quarkus/pom.xml`, neue Tests unter `engine/src/test/java`, `quarkus/src/test/java`, `application.properties`.

**Aufwand:** 3–5 Tage · **Risiko:** mittel (Testflakes, Zeit)

---

#### H14 — UI aus `index.html` herauslösen

**Ziel:** Wartbare UI-Struktur statt einer 2.370-Zeilen-Datei; minimale Test-/Lint-Pipeline.

**Befund:** `index.html` enthält HTML, CSS, JS, SSE-Client, Markdown-Renderer und Modals in einer Datei, ohne Build- oder Testschritt.

**Umsetzung:**
1. Aufteilen in `index.html`, `styles.css`, `app.js` und Module (`sse.js`, `markdown.js`, `modals.js`, `api.js`).
2. Statische Auslieferung über Quarkus (`META-INF/resources/`), keine Framework-Einführung nötig.
3. Minimale Prüfung: `node --check` für JS-Module, optional ESLint/Prettier; ein Playwright-Smoke-Test (Session anlegen, Prompt senden, Event empfangen) gegen `quarkus:dev`.
4. `index.html` auf < 300 Zeilen reduzieren.

**Tests/Akzeptanz:**
- UI-Funktionen (Session-Liste, Prompt, SSE, Modals) funktionieren unverändert.
- `index.html` < 300 Zeilen; JS-Module haben keine Syntaxfehler.
- Smoke-Test läuft in CI (optional, falls Playwright verfügbar).

**Betroffene Dateien:** `quarkus/src/main/resources/META-INF/resources/index.html`, neue `*.js`/`*.css`-Dateien, ggf. `package.json`/CI.

**Aufwand:** 2–3 Tage · **Risiko:** niedrig–mittel (UI-Regressionen)

---

#### H15 — Dokumentation und Betriebswerte konsistent machen

**Ziel:** `settings.md`, `application.properties` und `deploy/.env*` beschreiben dieselben Defaults.

**Befund:** Audit-Retention 2 Tage (Code/`.env`) vs. 30 Tage (settings.md Secure-Beispiel); Session-Retention 7 (Code) vs. 14 (`deploy/.env`). Preflight-/Triage-/Webhook-Properties sind nicht vollständig dokumentiert.

**Umsetzung:**
1. Defaults zentral festlegen und in `settings.md`, `application.properties`, `deploy/.env.example` synchron halten.
2. Neue Properties aus H2/H12 dokumentieren (`engine.preflight.*`, `engine.triage.*`, `engine.webhook.*`).
3. Abschnitt „Betriebswerte“ mit Tabelle (Property, Default, Empfehlung dev/secure, Wirkung) ergänzen.
4. Retention-Intervall und Cleanup-Strategie (H1/H4) dokumentieren.

**Tests/Akzeptanz:**
- Doku-Review: jede in `application.properties` verwendete Property hat einen Eintrag in `settings.md`.
- Keine widersprüchlichen Defaults zwischen Code, `.env.example` und Doku.

**Betroffene Dateien:** `docs/settings.md`, `docs/resilience.md`, `quarkus/src/main/resources/application.properties`, `deploy/.env.example`.

**Aufwand:** 0,5 Tag · **Risiko:** niedrig

---

### 4.4 Reihenfolge, Aufwand und Definition of Done

| ID | Maßnahme | Priorität | Aufwand | Abhängigkeit |
|---|---|---|---:|---|
| H1 | SQL-Dialekt/Retention | P1 | 0,5–1 T | — |
| H2 | Preflight-Cache | P1 | 0,5–1 T | — |
| H3 | Executors vereinheitlichen | P1 | 0,5–1 T | — |
| H4 | Retention in Tests aus | P1 | 0,25 T | — |
| H5 | Stille Catches sichtbar | P1 | 1 T | — |
| H6 | Engine-Split | P2 | 3–5 T | H1, H3, H4 |
| H7 | HttpApi-Split | P2 | 2–3 T | H10 |
| H8 | Persistenz in Adapter | P2 | 1–2 T | H1 |
| H9 | SQL/SQLite-Dedup | P2 | 2–3 T | H1, H8 |
| H10 | Session-Isolation | P2 | 2–4 T | — |
| H11 | ThreadLocals entfernen | P2 | 2–3 T | H3 |
| H12 | Unfertige Features | P2 | 2–4 T | Entscheidung |
| H13 | Coverage-Gate | P3 | 3–5 T | H4, H5, H6–H11 |
| H14 | UI-Split | P3 | 2–3 T | — |
| H15 | Doku-Konsistenz | P3 | 0,5 T | H1, H2, H12 |

**Empfohlene Sprints:**

1. **Sprint 1 (P1, ~3–4 Tage):** H1, H2, H3, H4, H5 — Korrektheit und deterministische Tests.
2. **Sprint 2 (P2 Security/Architektur, ~7–12 Tage):** H8, H10, H11, H12 — Persistenz, Isolation, Nebenläufigkeit, Feature-Entscheidungen.
3. **Sprint 3 (P2/P3 Struktur, ~12–20 Tage):** H6, H7, H9, H13, H14, H15 — God-Classes, Dedup, Coverage, UI, Doku.

**Definition of Done für den Review-Abschluss:**

- `mvn test` in `qoc/` grün; 3 Läufe ohne Flakes.
- `mvn verify` erzwingt das 80-%-Instructions-Gate für `engine`.
- Kein `now() - interval` und kein Persistenz-SQL außerhalb der Adapter (Readiness-/Preflight-Health-Checks als dokumentierte Ausnahme); ArchUnit in beiden Modulen.
- Zwei-User-Isolationstest grün (User B → 403/404, Admin → Zugriff).
- Keine `catch (ignored)`-Blöcke mehr in kritischen Pfaden ohne Begründung.
- `Engine.java` < 800 Zeilen, `HttpApi.java` < 300 Zeilen, `index.html` < 300 Zeilen.
- `docs/settings.md`, `application.properties` und `deploy/.env.example` sind konsistent.

---

## 5. Verifikationsprotokoll (Stand 2026-11-09)

| Prüfpunkt | Ergebnis | Beleg |
|---|---|---|
| LOC God-Classes | bestätigt | `wc -l` |
| Java-Dateien/LOC gesamt | bestätigt (597 / 55.717) | `find`/`wc` |
| ArchUnit-Regeln Engine | bestätigt (13 Regeln, inkl. `java.sql`-Regel) | `engine/.../arch/ArchitectureTest.java` |
| ArchUnit-Regeln Quarkus | bestätigt (nur 2 Regeln, keine SQL-Regel) | `quarkus/.../arch/ArchitectureTest.java:18-23` |
| Direktes SQL in `Engine`/`Durable*` | bestätigt | `Engine.java:845,852,1092-1107` (DELETE), `1540,1835` (Readiness/Preflight-JDBC); `Durable*Source` |
| PostgreSQL-`interval` in SQLite | bestätigt (Query schlägt fehl, wird geloggt) | `Engine.java:1095,1107`; `migration-sqlite/V3,V7` |
| SQL/SQLite-Duplikate | bestätigt (11 Paare) | `adapter/sql` vs. `adapter/sqlite` |
| Executor pro Tool-Call/Step | bestätigt | `ToolRuntime.java:100`, `Step.java:132` |
| ThreadLocal-Kopplung | bestätigt | `Engine.java:160-163,1801-1804`; `HttpProviderAdapter.java:49-51`; `CorrelationContext.java:11-12` |
| Roh-Threads | bestätigt | `Engine.java:514,756`; `DurableQuestionSource.java:99`; `HttpApi.java:1048` |
| LangChain4j ungenutzt | bestätigt | `ProviderProducer.java:49-66`; keine `engine.provider.type`-Referenz |
| `SessionWake`-Stub | bestätigt | `Engine.java:514-523` |
| `NoOpTriageService` | bestätigt | `Engine.java:739` |
| Subagent-Registry verdrahtet | **Korrektur:** ja, nur untracked; Dedup fehlt | `Engine.java:466-467,885,2368,2475,2484` |
| 47 stille Catches | bestätigt | `grep` in `engine`/`quarkus` |
| Session-Isolation | **Korrektur:** Liste/Get/Delete + 8 Endpunkte geschützt; Messages/Questions/Permissions ungeschützt | `HttpApi.java:160,181,195,241,268,370,426,447,552,574,586,608,620,1185` |
| `isOwner`/Admin-Bypass | bestätigt | `HttpApi.java:1518-1531` |
| Preflight pro Turn | bestätigt (DNS + HEAD, kein TTL; 404/405 gelten als erreichbar) | `DefaultPreflightChecker`; `Engine.java:1829` |
| PortableScanner | bestätigt (keine Variablenexpansion, nicht quote-aware) | `PortableScanner.java` |
| Native-Workaround | bestätigt | `deploy/Dockerfile.native:8`; `MainEventBusProcessor` im A2A-SDK |
| Testzahlen | bestätigt (763 Engine / 122 Quarkus, 0 Failures) | Surefire-Reports 2026-11-08 |
| Coverage 42,74 % / 10,74 % | bestätigt, aber Artefakte aus unterschiedlichen Läufen | `jacoco.csv`, `jacoco.exec`, Surefire-Zeitstempel |
| 80-%-Gate im POM | **Korrektur:** nicht vorhanden; nur AGENTS.md-Regel | `engine/pom.xml` (nur `prepare-agent`/`report`) |
| `PSQLException` in Reports | nicht reproduziert (0 Treffer) | Surefire-Reports |
| Retention-Test-Overrides | bestätigt: keine `%test`-Overrides | `application.properties` |
| Dirty Working Tree | bestätigt (5 modifiziert, 2 untracked) | `git status` |

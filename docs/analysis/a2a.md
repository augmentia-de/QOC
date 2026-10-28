# A2A Integration Analysis for QOC

**Stand:** 2026-10-28  
**Quelle:** [Quarkus Blog - Getting Started with Quarkus and A2A Java SDK 0.3.0](https://quarkus.io/blog/quarkus-a2a-java-0-3-0-alpha-release/)  
**Ziel:** Möglichkeiten aufzeigen, wie QOC A2A (Agent-to-Agent) nutzen kann

## 1. Was ist A2A?

Das [Agent2Agent (A2A) Protokoll](https://a2a-protocol.org/latest/specification/) ist ein offener Standard (ursprünglich von Google, jetzt Linux Foundation), um KI-Agenten unabhängig von Framework/Sprache/Vendor kommunizieren zu lassen. Version 0.3.0 bringt u.a.:

- **gRPC-Transport** zusätzlich zu JSON-RPC 2.0
- **HTTP+JSON/REST** soll demnächst folgen
- Überarbeitete Client-API über `ClientBuilder`
- Bessere Multi-Transport-Unterstützung via `AgentCard.additionalInterfaces`
- Vereinheitlichte Event-Verarbeitung auf Client-Seite

## 2. A2A Java SDK 0.3.0 (Quarkus)

### Server-Seite
Dependencies (Gruppe `io.github.a2asdk`, vorübergehend):

| Transport | Dependency | Bemerkung |
|---|---|---|
| JSON-RPC 2.0 | `a2a-java-sdk-reference-jsonrpc` | Stellt HTTP-Endpoint für JSON-RPC bereit, AgentCard unter `/.well-known/agent-card.json` |
| gRPC | `a2a-java-sdk-reference-grpc` | Stellt gRPC-Service bereit |

Beide können parallel genutzt werden. `AgentCard` muss einen Primary-Endpoint + `preferredTransport` + `additionalInterfaces` für alle unterstützten Transports enthalten (`AgentInterface` pro Transport).

### Client-Seite
```xml
<dependency>
  <groupId>io.github.a2asdk</groupId>
  <artifactId>a2a-java-sdk-client</artifactId>
</dependency>
<!-- optional für gRPC -->
<dependency>
  <groupId>io.github.a2asdk</groupId>
  <artifactId>a2a-java-sdk-client-transport-grpc</artifactId>
</dependency>
```

`ClientBuilder` wählt Transport anhand `AgentCard` + Client-Konfiguration. `ClientConfig` steuert `acceptedOutputModes`, Streaming etc. Events werden über `BiConsumer<ClientEvent, AgentCard>` empfangen (`MessageEvent`, `TaskEvent`, `TaskUpdateEvent`). Streaming-Fehler über separaten Handler.

## 3. QOC-Architektur im Überblick (für A2A relevant)

Aus `README.md` und Code:

- **Module**: `engine/` (core/runtime/adapter, reines Java), `quarkus/` (HTTP/SSE, CDI, Wiring), `docs/` (Spec ist Quelle der Wahrheit)
- **HTTP-API**: REST/SSE unter `/api/*` (siehe `HttpApi.java`) – Sessions, Prompts, Events (SSE), Permissions, Questions, Tools/Files, MCP, Agents/Skills/Models
- **Session-Engine**: Inbox (`STEER`/`QUEUE`), Ausführung, Kompaktierung/Revert, parallele Tool-Calls, Subagents
- **Tools**: Registry, Permission-Enforcement, viele Builtins (fs, shell, web, skill, subagent, todo, question, worktree, MCP)
- **Provider**: über Engine/Ports, modelliert als Provider-Adapter
- **MCP**: bereits integriert (Server-Liste, Status, OAuth-Flow)
- **Security**: Auth-Modes, Principal-Propagation, Trusted-Proxy, Webhook-Guardrails, Audit

Wichtige Punkte für A2A:
- QOC hat bereits ein eigenes Session/Task-Modell und eigene HTTP/SSE-API
- Agent-Definitionen existieren (`/api/agent`)
- Tools/MCP sind vorhanden
- Quarkus wird bereits genutzt

## 4. Integrationsmöglichkeiten für QOC

### 4.1 QOC als A2A-Server (exponiert)
QOC-Agenten für andere A2A-Clients zugänglich machen.

**Ziele:**
- Externe Agenten (z.B. andere Frameworks) können QOC über A2A aufrufen
- Interoperabilität ohne QOC-spezifisches Client-SDK
- Multi-Transport (JSON-RPC + gRPC)

**Ansatz:**
1. **AgentCard-Produktion**: Für jeden QOC-Agenten (oder konfigurierbaren Satz) eine `AgentCard` erzeugen. Mapping aus QOC-Agent-Definition (`/api/agent`) + Skills/Capabilities.
2. **A2A-Server-Endpoints**: Quarkus-Module/Extension für A2A-Server referenzieren. `/.well-known/agent-card.json` automatisch exponieren.
3. **Task/Message-Mapping**: A2A-`Task`/`Message`/`Part` auf QOC-Session + Prompt abbilden. QOC-Sessions können A2A-Tasks zugeordnet werden.
4. **Streaming**: A2A-Streaming-Events auf QOC-SSE/Event-Stream mappen oder direkt A2A-Transport nutzen.
5. **AuthN/Z**: A2A-Requests gegen QOC-Security (Principal/Permissions) validieren.

**Vorteile:** Interoperabilität, Ökosystem-Zugriff  
**Aufwand:** Mittel-Hoch (Mapping A2A<->QOC-Domain, Task-Lifecycle)

### 4.2 QOC als A2A-Client (konsumiert)
QOC kann andere A2A-Server aufrufen und deren Fähigkeiten als Tools nutzen.

**Ziele:**
- Externe A2A-Agents als Tools in QOC-Sessions einbinden
- Agent-Handoffs / Delegation
- Hybride Multi-Agent-Workflows

**Ansatz:**
1. **Client-Integration**: `a2a-java-sdk-client` + ggf. gRPC-Transport in `quarkus/` hinzufügen
2. **A2A-Tool-Fabrik**: Aus `AgentCard` Tools generieren (z.B. `a2a.<agentId>.<skillId>`). Tool-Input/Output an A2A-Message/Task mappen
3. **Discovery**: Konfiguration von A2A-Endpoints (URLs) in QOC-Config/Settings
4. **Task-Verwaltung**: A2A-Client (`Client`) verwalten, Tasks verfolgen, auf Updates reagieren
5. **Permissions**: Aufrufe externer A2A-Agents durch QOC-Permissions abbilden
6. **Auth**: Tokens/Credentials für externe A2A-Server (analog MCP-OAuth)

**Vorteile:** Sehr praktisch, nutzt bestehendes Tool-System  
**Aufwand:** Mittel (Tool-Adapter, Discovery, Config)

### 4.3 Hybrid: A2A als Transport für QOC-MCP/Tools
A2A könnte MCP ergänzen oder teilweise ablösen, aber MCP ist bereits stark integriert. Wahrscheinlicher ist **komplementär**: A2A für Agent-zu-Agent, MCP für Tool-zu-Server.

### 4.4 Subagent-Nutzung über A2A
QOC hat bereits Subagents. A2A könnte Remote-Subagents ermöglichen (andere Prozesse/Services).

## 5. Konkreter Implementierungsplan für QOC

### Phase 0: Vorbereitung & Analyse (abgeschlossen)
- [x] A2A-Spec lesen (Task/Message/Part, AgentCard, Transports, Streaming)
- [x] A2A-Java-SDK API prüfen
- [x] QOC-Tool-Contract & Permission-System verstehen
- [x] Entscheidung: Client zuerst, danach Server

### Phase 1: A2A-Client als QOC-Tool (umgesetzt)
Externe A2A-Agents werden als QOC-Tools registriert.

**Umsetzung:**
1. **Dependencies**: `org.a2aproject.sdk:a2a-java-sdk-client:1.4.0.Final`
   (optional `a2a-java-sdk-client-transport-grpc`).
2. **Config**: `engine.a2a.urls` (kommasepariert) und
   `engine.a2a.endpoints` (`url=...|id=...|tool=...|enabled=true;`).
3. **Tool-Provider**: `runtime/tool/a2a/A2AToolProvider` lädt AgentCards und
   baut je Endpoint ein Tool.
4. **Tool-Implementierung**: `A2ATool` mappt JSON-Input auf eine A2A-Message,
   ruft den Agenten blockierend auf und mappt Task/Message-Antworten auf
   `ToolResult`.
5. **Registry-Integration**: Die Tools laufen durch dieselbe Registry,
   Permission- und Hook-Kette wie Builtins.
6. **Egress**: Host-/IP-Allowlists und TLS-Prüfung gelten auch für A2A-Ziele.

### Phase 2: A2A-Server (QOC exponiert) (umgesetzt)
QOC-Agenten werden als A2A-Agent angeboten. Transport ist JSON-RPC.

**Umsetzung:**
1. **Dependencies**: `a2a-java-sdk-reference-jsonrpc` und
   `a2a-java-sdk-client` in `1.4.0.Final`. Die SDK-eigenen CDI-Beans
   (`TaskStore`, `QueueManager`, `DefaultRequestHandler`,
   `MainEventBusProcessor`, `A2AServerRoutes`) werden mitgenutzt.
2. **AgentCard-Producer**: `A2AAgentCardProducer` erzeugt die Card aus
   `AgentCatalogue`; jeder sichtbare QOC-Agent wird ein Skill. Die Card
   deklariert JSON-RPC für Protokollversion 1.0 und 0.3.
3. **AgentExecutor**: `QocAgentExecutor` übersetzt A2A-Tasks in
   QOC-Sessions und Prompts und liefert das Ergebnis als Text-Artifact.
4. **Session-Mapping**: `A2ASessionRegistry` ordnet `contextId` (bzw. `taskId`
   ohne Context) einer QOC-Session zu, LRU-begrenzt auf 1000 Kontexte.
5. **Streaming**: Die Card meldet `streaming=true`; die Ausführung ist aktuell
   blockierend und liefert ein finales Artifact. SSE-Feintuning folgt.
6. **Auth**: `A2AAuthenticationMechanism` versorgt die Vert.x-Routen mit einer
   Quarkus-Identität (none/basic/proxy; OIDC überlässt sie dem OIDC-Modul).
   `A2ATaskAuthorizationProvider` erzwingt Task-Ownership und ist fail-closed.
7. **Opt-in**: `engine.a2a.server.enabled=false` ist Default; ein Route-Gate
   beantwortet die A2A-Pfade sonst mit `404`.
8. **Konfiguration**:
   - `engine.a2a.server.enabled`
   - `engine.a2a.server.name`
   - `engine.a2a.server.description`
   - `engine.a2a.server.version`
   - `engine.a2a.server.base-url` (öffentliche URL in der AgentCard)
   - `engine.a2a.server.agent` (`none` = Primär-Agent)

### Phase 3: Discovery & UI (umgesetzt)

- `GET /api/a2a/agents` listet alle konfigurierten externen Agents mit
  effektiver Konfiguration, AgentCard-Metadaten (Name, Version, Protokoll,
  Skills), Tool-Namen und Status (`ok`, `error`, `disabled`).
- `POST /api/a2a/agents/refresh` (oder `GET ...?refresh=true`) invalidiert den
  Card-Cache und lädt die Karten neu.
- **AgentCard-Caching**: gemeinsamer TTL-Cache für Tool-Registrierung und
  Discovery (`engine.a2a.cache.ttl-seconds`, Default 300, `0` deaktiviert,
  Maximum 86400). Fehlgeschlagene Fetches werden nicht gecacht; der nächste
  Aufruf versucht es erneut. Der Cache ist prozesslokal und thread-safe.
- **UI**: Button „A2A" in der Web-Oberfläche öffnet eine read-only Übersicht
  der konfigurierten Agents inkl. Status, Skills und Refresh.
- **Konfiguration** bleibt datei-/umgebungsbasiert (`engine.a2a.urls`,
  `engine.a2a.endpoints`). Eine Bearbeitung zur Laufzeit ist bewusst nicht
  enthalten, weil das eine Neuregistrierung der Tool-Snapshots erfordern würde.


## 6. Bewertung nach QOC-Prinzipien

QOC folgt: reines Java-Core, Ports/Adapter, Spec als Quelle der Wahrheit, ArchUnit, Tests.

| Aspekt | Bewertung | Bemerkung |
|---|---|---|
| **Core-Freiheit** | Gut | A2A-Client/Server nur in `runtime.tool` oder `adapter.a2a` / `quarkus/`, nicht in `core` |
| **Tool-System** | Sehr gut | Passt perfekt - A2A-Agents werden wie Tools behandelt |
| **Permissions** | Gut | Bereits vorhanden, erweiterbar |
| **MCP-Kompatibilität** | Gut | Komplementär, nicht konfliktierend |
| **Transports** | Gut | JSON-RPC/gRPC über Quarkus unterstützt |
| **Aufwand Client-first** | Gering-Mittel | Sofort nutzbar, kein eigener Server nötig |

## 7. Empfehlungen

**Empfehlung: Client-first, Server opt-in (umgesetzt)**

Gründe:
1. **Minimal invasiv**: Client ist nur ein Tool-Adapter, keine Änderung an Core-Session-Logik
2. **Sofortiger Nutzen**: QOC kann andere Agents delegieren
3. **Nutzt bestehendes Design**: Tool-Registry, Permissions, Execution-Chain
4. **Testbar**: Gegen den eigenen Server und externe A2A-Agents testbar
5. **Server bleibt opt-in**: keine ungewollte Exposition

**Nächste konkrete Schritte:**
1. Push-Notifications an QOC-Events anbinden (optional, niedrige Priorität)
2. Egress-Gating über Custom-HTTP-Client auf Transport-Ebene (aktuell Application-Level-Guard)
3. Tenant-Support (`/{tenant}`) — separat geplant
4. Automatisierte Interop-Suite gegen externe Referenz-Agents

## 8. Offene Fragen

- [x] SDK-Version finalisiert (`org.a2aproject.sdk:1.4.0.Final`)
- [x] Skill-to-Tool-Mapping: ein Tool pro Agent, Description mit Skills angereichert, optionaler `skill`-Parameter
- [x] Task-Lifecycle: Blocking und SSE-Streaming (inkrementelle Chunks via EventBus) implementiert
- [x] Auth für externe A2A-Agents: `auth=bearer:...`/`basic:...`/`headers=...` in `engine.a2a.endpoints`
- [x] Content-Typen: TextPart, DataPart, FilePart werden client- und serverseitig verarbeitet
- [x] Egress-Gating: Application-Level-Guard (`A2AEgress`) mit Host/IP-Allowlists und TLS; Transport-Level offen
- [ ] Wie mappt man QOC-Tool-Schemas auf A2A Message Parts vollständig? (DataPart genutzt; FilePart als Referenz)
- [ ] Tenant-Support (`/{tenant}`) — separat geplant
- [ ] Caching von AgentCards auf Server-Seite? (Client-Cache implementiert)

## 9. Verbesserungen / TODOs (nicht blockierend)

### 9.1 Push-Notifications (optional, niedrige Priorität)
- **Was:** SDK unterstützt `TaskPushNotificationConfig` (Webhook-URL, Auth) für asynchrone Task-Updates. QOC kann als Push-Empfänger fungieren.
- **Warum noch nicht:** Erfordert `PushNotificationConfigStore`-Implementierung und Anbindung an QOC-EventBus/Webhooks. Nur für Clients relevant, die nicht blockierend warten können.
- **Aufwand:** mittel. **Priorität:** niedrig.

### 9.2 Egress-Gating auf Transport-Ebene
- **Was:** Aktuell Application-Level-Guard in `A2AEgress` (prüft vor Card-Fetch und vor `sendMessage`). Ein Custom-`A2AHttpClient` (JDK-basiert) würde die Prüfung auf Transport-Ebene durchsetzen.
- **Warum noch nicht:** Der SDK-HTTP-Client ist intern; ein Wrapper erfordert Implementierung aller Builder-Interfaces (`GetBuilder`, `PostBuilder`, `DeleteBuilder`).
- **Aufwand:** mittel-hoch. **Priorität:** mittel (Sicherheit).

### 9.3 Automatisierte Interop-Suite
- **Was:** Docker-Konzept (`docs/spec/qualitaet/04-docker-a2a-mcp-e2e.md`) und Compose-Stack (`deploy/docker-compose.a2a-mcp-e2e.yml`) existieren und wurden manuell getestet. Keine automatisierte Test-Suite.
- **Warum noch nicht:** Erfordert Runner-Container mit SDK-Client und Szenarien A2A-01…10 / MCP-01…06.
- **Aufwand:** mittel. **Priorität:** mittel (Qualitätssicherung).

### 9.4 Server-Card-Caching
- **Was:** AgentCard wird bei jedem Request neu aus `AgentCatalogue` gebaut. Caching könnte sinnvoll sein.
- **Aufwand:** gering. **Priorität:** niedrig.

### 9.5 QOC-Tool-Schema → A2A Message Parts
- **Was:** Aktuell werden QOC-Tool-Ergebnisse als Text an den Remote-Agent gesendet. DataPart/FilePart werden clientseitig verarbeitet, aber das Mapping von QOC-Tool-Schemas auf A2A Message Parts ist nicht vollständig.
- **Aufwand:** mittel. **Priorität:** niedrig.

## Referenzen

- [A2A Spec](https://a2a-protocol.org/latest/specification/)
- [A2A Java SDK](https://github.com/a2aproject/a2a-java)
- [A2A Samples](https://github.com/a2aproject/a2a-samples)
- [Quarkus Blog Post](https://quarkus.io/blog/quarkus-a2a-java-0-3-0-alpha-release/)
- [QOC README](../README.md)
- [QOC HttpApi](../../quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java)

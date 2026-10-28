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

### Phase 0: Vorbereitung & Analyse
- [ ] A2A-Spec 0.3.0 lesen (Task/Message/Part, AgentCard, Transports, Streaming)
- [ ] A2A-Java-SDK API im Detail prüfen (Packages `io.a2a.*`)
- [ ] QOC-Tool-Contract & Permission-System verstehen (bereits vorhanden)
- [ ] Entscheidung: Server/Client/Beides zuerst?

### Phase 1: A2A-Client als QOC-Tool (niedrigster Aufwand, hoher Nutzen)
Am einfachsten: Externe A2A-Agents als aufrufbare Tools integrieren.

**Änderungen:**
1. **Dependencies** in `qoc/pom.xml` / `quarkus/pom.xml`:
   ```xml
   <dependency>
       <groupId>io.github.a2asdk</groupId>
       <artifactId>a2a-java-sdk-client</artifactId>
       <version>0.3.0.Alpha1</version> <!-- oder aktuell -->
   </dependency>
   <dependency>
       <groupId>io.github.a2asdk</groupId>
       <artifactId>a2a-java-sdk-client-transport-grpc</artifactId>
       <version>0.3.0.Alpha1</version>
       <optional>true</optional>
   </dependency>
   ```
2. **Config**: `engine.a2a.client.endpoints[]` mit `{id, url, enabled, auth?}`
3. **Tool-Provider**: `runtime/tool/a2a/A2AToolProvider` erstellt Tools aus AgentCards
4. **Tool-Implementierung**: `A2AAgentTool` nutzt `ClientBuilder` + `Client`, mappt Input->Message, ruft Task/Message, mappt Response
5. **Registry-Integration**: Tools wie andere Builtins registrieren
6. **Permissions**: Eigene Permission-Action `a2a.call` oder bestehende Regeln

**Mapping-Ideen:**
- AgentCard-Skills → separate Tools oder ein Tool pro Skill
- Tool-Input (JSON) → A2A Message mit Parts (TextPart/DataPart)
- A2A Task/Message Response → QOC Tool-Output (Content + Metadata)

### Phase 2: A2A-Server (QOC exponiert)
QOC-Agenten als A2A-Agents anbieten.

**Änderungen:**
1. **Dependencies**: `a2a-java-sdk-reference-jsonrpc`, `a2a-java-sdk-reference-grpc`
2. **AgentCard-Producer** (`quarkus/.../a2a/AgentCardProducer`): Generiert Card aus QOC-Agenten + Capabilities
3. **Task-Handler**: A2A-Requests → QOC-Session/Prompt, Responses zurück mappen
4. **Session-Mapping**: A2A-Task-ID <-> QOC-Session-ID
5. **Streaming**: QOC-Events an A2A-Client streamen
6. **Auth**: In QOC-Security integrieren

### Phase 3: Discovery & UI
- `/api/a2a/agents` Endpoint für konfigurierte externe Agents
- Config-UI/CLI für A2A-Endpoints
- AgentCard-Caching

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

**Empfehlung: Start mit A2A-Client als Tool (Phase 1)**

Gründe:
1. **Minimal invasiv**: Nur Tool-Adapter + Config, keine Änderung an Core-Session-Logik
2. **Sofortiger Nutzen**: QOC kann andere Agents delegieren
3. **Nutzt bestehendes Design**: Tool-Registry, Permissions, Execution-Chain
4. **Testbar**: Gegen A2A-Sample-Agents testbar
5. **Zukünftig erweiterbar**: Server-Integration kann später folgen

**Nächste konkrete Schritte:**
1. A2A-Java-SDK 0.3.0 Alpha1 in Dependencies aufnehmen
2. `docs/analysis/a2a.md` reviewen (dieses Dokument)
3. Proof-of-Concept: Einfacher A2A-Client-Tool für einen bekannten Agent
4. Config-Schema erweitern
5. Tests hinzufügen

## 8. Offene Fragen

- [ ] SDK-Version finalisieren (`io.github.a2asdk` vs ggf. anderes GroupId)
- [ ] Wie mappt man QOC-Tool-Schemas auf A2A Message Parts?
- [ ] Sollen alle Skills eines Agents als separate Tools oder als ein Tool exponiert werden?
- [ ] Task-Lifecycle: Blocking vs Streaming?
- [ ] Auth für externe A2A-Agents (API-Key/OAuth)?
- [ ] Caching von AgentCards?
- [ ] Error-Mapping A2A -> QOC Tool-Errors

## Referenzen

- [A2A Spec](https://a2a-protocol.org/latest/specification/)
- [A2A Java SDK](https://github.com/a2aproject/a2a-java)
- [A2A Samples](https://github.com/a2aproject/a2a-samples)
- [Quarkus Blog Post](https://quarkus.io/blog/quarkus-a2a-java-0-3-0-alpha-release/)
- [QOC README](../README.md)
- [QOC HttpApi](../../quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java)

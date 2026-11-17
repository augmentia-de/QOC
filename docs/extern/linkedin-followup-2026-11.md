# LinkedIn-Beitrag: QOC – vom Demo zum produktionsreifen Agenten-Service (Follow-up)

> Ablage: `docs/linkedin-followup-2026-11.md` (außerhalb des Git-Repos, nicht committed)
> Sprache: Deutsch (wie der Vorgänger-Beitrag)
> Projektname im Text: **QOC**

---

## Beitrag (kopierbar für LinkedIn)

Warum ich OpenCode nach Java portiert habe – Teil 2: Was QOC produktionsreif macht.

Im ersten Teil ging es um die Grundlage: eine spec-getriebene Java-Portierung von OpenCode, mit Session-Engine, Tools, MCP, A2A, asynchronen Approvals und idempenter Runtime. Die Frage, die danach kam: „Und wie setze ich das jetzt wirklich ein, ohne dass der Agent alles darf?"

Genau dort setzt QOC an. Drei Themen haben den größten Unterschied gemacht.

🔹 1. Security: von allow-all zu least-privilege

Am Anfang stand, wie bei vielen Agenten-Demos, ein großzügiges allow-all: der Agent durfte lesen, schreiben, ausführen. Das reicht für einen Prototypen – nicht für Produktion. QOC hat das in Stufen umgebaut:

- Permission-Default ist jetzt least-privilege (deny-by-default). Erlaubt wird nur, was explizit freigegeben ist.
- Shell-Sandbox (bubblewrap) begrenzt Schreibzugriffe auf den Workspace; in Containern kommt die Grenze aus den Mounts.
- Ask-Mode steuert, was bei fehlender Freigabe passiert: deny, allow oder interaktiv.
- Kurzlebige, origin-bearing Tool-Tokens (60 s) für Downstream-Aufrufe.

💡 Der Wert: der Agent bekommt genau die Rechte, die er braucht – und nicht mehr. Least-Privilege als Default, nicht als Nachrüstung.

🔹 2. OIDC statt „lokal admin"

Für den ersten Entwickler-Modus reichte ein Login-freier Loopback mit lokalem Admin. Für Produktion nicht. QOC spricht jetzt OIDC (Keycloak im Dev-Profil, Auth0 in Prod) und löst Rollen aus dem JWT (realm_access/roles, permissions, groups). Dazu Trusted-Proxy-Auth (nginx/LB) und HTTP Basic als Fallback. Der Role-Claim-Pfad ist pro Profil konfiguriierbar, fehlende Claims führen fail-closed zu 403.

💡 Der Wert: derselbe Code läuft lokal ohne Login und in Produktion mit echter Identität und Rollen – kein „if (dev)"-Codepfad.

🔹 3. Dieselbe Engine als deterministische Workflow-Orchestrierung

Der LLM-Loop (Prompt → Kontext → Tool-Calls → Ergebnis) ist erprobt und nachprüfbar. QOC nutzt ihn jetzt auch für Workflow-Orchestrierung: Workflows werden topologisch sortiert, Knoten (Agenten, Entscheidungen, Forks/Joins, Loops, MCP-Calls) laufen in der gleichen Engine. Der Zustand liegt im Journal (Event-Sourcing, in der DB oder in SQLite/In-Memory) – jeder Schritt ist nachvollziehbar, jeder Run wiederholbar und fortsetzbar.

💡 Der Wert: kein zweiter, ungetesteter Orchestrierungs-Stack. Deterministisch, weil der Loop derselbe ist, der schon in Produktion läuft. Auditable, weil alles im Journal landet.

🔹 4. In-Memory-Variante für Helper-Aufgaben

Nicht jede Aufgabe braucht eine Datenbank. QOC hat einen In-Memory-Modus: Sessions, Conversations, Journal, Claims und Tool-Outputs im Heap, keine Persistenz, kein Restart-Recovery. Ideal für eingebettete Helper, ephemere Sessions, Tests und Single-Process-Deployments. Der Footprint bleibt klein (Default-Budget 300 MB, LRU-begrenzte Tool-Outputs).

💡 Der Wert: dieselbe Engine, zwei Betriebsmodi – durable für Produktion, flüchtig für Helfer und Tests. Kein zweites Framework.

Die AI liefert die Intelligenz. QOC liefert die Betriebsreife – und jetzt auch die Sicherheit, die Identität und die Orchestrierung, die aus einem Demo ein Produkt machen.

🚀 Was fehlt noch auf eurem Weg vom Agenten-Demo zum Agenten-Service?

📌 Repository: https://github.com/augmentia-de/QOC

#AIAgents #Harness #MCP #LangChain4j #Quarkus #Java #OIDC #RBAC #SpecDrivenDevelopment #WorkflowOrchestration

---

## Hinweise zur Veröffentlichung

- Der Beitrag ist die Fortsetzung des Posts „Warum ich OpenCode nach Java portiert habe" (31.10.2026).
- Fakten gegen den Code geprüft:
  - `engine.permission.default=least-privilege` (Default), `allow-all` als Alternative (`application.properties`).
  - Shell-Sandbox: `engine.shell.sandbox.mode=workspace-write`, bwrap; in Docker deaktiviert (unprivilegiert) (`06-security-hardening.md`).
  - Ask-Mode: `deny | allow | interactive` (`engine.permission.ask-mode`).
  - Tool-Token: `engine.security.tool-token.ttl-seconds=60` (Default).
  - OIDC: `quarkus.oidc.enabled`, Keycloak (`%dev`) / Auth0 (`%prod`), Rollen aus `realm_access.roles`/`permissions`/`groups` (`OidcPrincipalProducer`, `SecurityConfig`).
  - Trusted-Proxy: `TrustedProxyFilter`, `X-Forwarded-*`.
  - Workflow-Orchestrierung: `DefaultNodeDispatcher`, `TopologicalSort`, `WorkflowExecutor`, Journal-Events (`WorkflowRunStarted`, `WorkflowNodeCompleted`), DB/SQLite/In-Memory (`Workflow.md`, `WorkflowStore`).
  - In-Memory: `engine.storage=memory`, `RuntimeState`, No-Op-Adapter für optionale Stores, `engine.memory.max-bytes=314572800` (300 MiB), LRU-capped Tool-Outputs (max 256).
  - Lightweight: e2-micro-Erwähnung aus dem Vorgänger-Beitrag beibehalten.
- Hashtags am Ende anpassen (LinkedIn erlaubt max. ~5–10 Hashtags für beste Reichweite; aktuell 11).
- Bildvorschlag: Screenshot der Workflow-UI (GraphEditor) oder das Security-Diagramm aus `docs/diagrams/`.

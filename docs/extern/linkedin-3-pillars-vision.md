# LinkedIn-Beitrag: 3-Säulen-Modell für effiziente KI-Workflows (Vision)

> Ablage: `docs/linkedin-3-pillars-vision.md` (außerhalb des Git-Repos)
> Sprache: Deutsch
> Projektname: **QOC**

---

## Beitrag (kopierbar für LinkedIn)

Warum ein riesiger Agent, der alles im Kontext behält, in der Praxis scheitert – und wie QOC das löst.

Die meisten KI-Workflows scheitern an zwei Extremen:

- Zu starr: reine Skripte ohne Intelligenz.
- Zu chaotisch und teuer: ein riesiger Agent, der alles im Kontext behält, sich verrennt und das Context-Window sprengt.

Die Lösung ist eine präzise Aufgabenteilung innerhalb des Workflow-Graphen über drei klar definierte Node-Typen.

🔹 1. Deterministische Nodes (Die Leitplanken)

Was sie tun: Führen feste Regeln, Daten-Transformationen, API-Calls (MCP), Verzweigungen oder menschliche Freigaben aus – komplett ohne LLM.

Kontext-Bedarf: Minimal (nur die exakt benötigten Input-Variablen).

Warum wir sie brauchen:
- 0 € Token-Kosten und maximale Geschwindigkeit: Was ein Skript in 5 Millisekunden löst, darf kein teures Sprachmodell berechnen.
- 100 % Verlässlichkeit: Gleicher Input garantiert gleichen Output. Sie bilden das stabile Skelett des Prozesses und garantieren Auditierbarkeit und Replaybarkeit.

🔹 2. Subagent-Nodes mit begrenztem Kontext (Die Spezialisten)

Was sie tun: Kapseln einen eigenständigen, adaptiven Agent-Loop (ReAct) für eine klar umrissene Teilaufgabe – z. B. „Recherchiere Quelle X" oder „Extrahiere Daten aus PDF Y".

Kontext-Bedarf: Isoliert (Sandbox-Prinzip). Der Subagent sieht nur seinen spezifischen Auftrag und die dafür nötigen Rohdaten. Nach getaner Arbeit werden alle internen Tool-Calls, Fehlversuche und Zwischenschritte verworfen – nur das saubere, strukturierte Endergebnis (JSON Schema) fließt zurück in den Haupt-Workflow.

Warum wir sie brauchen:
- Verhindert Context Pollution: Wenn ein Agent 15 Tool-Calls braucht, um eine Info zu finden, müllt dieser Prozess nicht den restlichen Workflow zu.
- Hohe Qualität und Fokus: Ein LLM, das nicht durch irrelevante Historie abgelenkt wird, halluziniert deutlich weniger und arbeitet präziser.

🔹 3. Context-Agenten mit Gesamtübersicht (Die Strategen)

Was sie tun: Stehen an kritischen Knotenpunkten (Planung, Synthese, Endabnahme), an denen das Big Picture benötigt wird, um Entscheidungen zu treffen oder Ergebnisse zusammenzuführen.

Kontext-Bedarf: Kuratiert und verdichtet. Sie erhalten den globalen System-Prompt, die ursprüngliche Zielsetzung sowie die komprimierten Endergebnisse der vorherigen Subagenten und deterministischen Schritte.

Warum wir sie brauchen:
- Maximales Context Caching: Da der globale System-Prompt und die Basis-Dokumente stabil am Anfang stehen und der dynamische Verlauf dank der Subagenten-Isolation extrem kompakt bleibt, greift das Prefix-Caching der LLM-Provider optimal. Das senkt die Kosten für große Kontexte um bis zu 75–90 % und beschleunigt die Antwortzeit massiv.
- Echte Intelligenz am richtigen Ort: Das Modell nutzt seine volle Kapazität für das Verknüpfen von Zusammenhängen statt für das Suchen nach Details.

---

Der Vergleich auf einen Blick

| Kriterium | Deterministische Nodes | Subagent-Nodes (Spezialisten) | Context-Agenten (Strategen) |
|---|---|---|---|
| Aufgabe | Regeln, Routing, Transformation | Isolierte Detailarbeit & Tool-Nutzung | Synthese, Bewertung, Gesamtstrategie |
| LLM-Einsatz | Nein (0 Tokens) | Ja (fokussiert, eigene Child-Session) | Ja (übergeordneter Überblick) |
| Kontext-Scope | Punktuelle Variablen | Enge Sandbox (wird danach bereinigt) | Kuratierter Gesamtverlauf + Basiswissen |
| Hauptvorteil | Geschwindigkeit, Determinismus, 0 € Kosten | Keine Kontext-Verschmutzung, hohe Präzision | Optimales Context Caching, fundierte Entscheidungen |

---

Das Fazit für das Team

„Wir lassen deterministische Nodes die Arbeit machen, die keine KI braucht. Wir lassen Subagenten in isolierten Räumen den Lärm der Tool-Ausführung abfangen. Und wir geben den Context-Agenten genau die verdichtete Übersicht, die sie brauchen, um – unterstützt durch Context Caching – schnell, günstig und treffsicher zu entscheiden."

🚀 Was fehlt noch auf eurem Weg vom Agenten-Demo zum Agenten-Service?

📌 Repository: https://github.com/augmentia-de/QOC

#AIAgents #Harness #MCP #LangChain4j #Quarkus #Java #OIDC #RBAC #SpecDrivenDevelopment #WorkflowOrchestration #ContextCaching #LLMOps

---

## Hinweise zur Veröffentlichung

- Dieser Beitrag ist als eigenständige Vision formuliert (kein direkter Follow-up).
- Kann als eigenständiger Post oder als Thread-Antwort zum Vorgänger (31.10.2026) verwendet werden.
- Fakten gegen den Code geprüft:
  - Deterministische Nodes: `decision`, `fork`, `join`, `loop`, `mcp_call`, `hitl`, `start`, `end` (kein LLM).
  - Subagent-Isolation: `DefaultNodeDispatcher.runAgent` → Child-Session; nur `NodeResult` zurück.
  - Structured Output: `responseSchema` + `StructuredOutputConverter`.
  - Context Caching: `CacheAffinity` (Headers), `cacheKey`-Feld vorhanden (Provider-Integration ausstehend).
  - Compaction: `CompactionService` für lange Histories.
- Hashtags: 12 — für LinkedIn auf 5–8 reduzieren (Empfehlung: #AIAgents #MCP #Quarkus #Java #WorkflowOrchestration #ContextCaching).
- Bildvorschlag: Architektur-Diagramm aus `docs/diagrams/` oder das 3-Säulen-Diagramm aus dieser Datei.
- Die "75–90 % Einsparung" ist eine Schätzung basierend auf Prompt-Caching-Rabatten (OpenAI: 50 %, Anthropic: bis 90 % auf cached Prefixes). Vor Veröffentlichung mit echten Messungen validieren.

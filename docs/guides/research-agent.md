# Research Agent – Asynchrone und parallele Recherche

> **Status:** Design · 2026-11-04
> **Zweck:** Architektur und Nutzung eines Research-Agents, der Web-Suche,
> Fetch, Analyse, Zusammenfassung und Prüfung durchführt und Ergebnisse an
> den Aufrufer zurückgibt. Unterstützt asynchrone und parallele Ausführung.

## 1. Research-Agent als Agent-Definition

Der Research-Agent ist ein Subagent mit spezifischen Tools:

```markdown
---
name: research-agent
mode: subagent
hidden: true
steps: 20
toolAllow: [websearch, webfetch, read, write, glob, grep]
toolDeny: [shell, edit, patch]
permissions:
  websearch: allow
  webfetch: allow
  read: allow
  write: ask
---
Du bist ein Research-Agent. Deine Aufgabe:
1. Suche im Web nach relevanten Informationen
2. Hole die wichtigsten Quellen (webfetch)
3. Analysiere und vergleiche die Ergebnisse
4. Erstelle eine strukturierte Zusammenfassung
5. Prüfe die Quellen auf Konsistenz und Aktualität
6. Gib das Ergebnis als strukturierten Report zurück

Antworte immer mit: Zusammenfassung, Quellen, Konfidenz-Score.
```

## 2. Drei Ausführungsmodi

### Modus A: Synchron (ein Thema, blockierend)

Aufrufer wartet auf das Ergebnis. Geeignet für kurze Recherchen (< 2 Min.).

### Modus B: Asynchron (ein Thema, nicht blockierend)

`background=true` startet einen Daemon-Thread. Der Aufrufer erhält sofort eine
Session-ID und kann per Polling (`GET /api/session/{id}/messages`) oder SSE
(`GET /api/event`) das Ergebnis abholen.

### Modus C: Parallel (zwei Themen gleichzeitig)

Ein Orchestrator-Agent spawnt mehrere Research-Subagents parallel. Wenn der
LLM-Provider parallele Tool-Calls unterstützt, werden die Subagents in einem
Schritt gestartet. Die Engine führt sie parallel aus (max. 4 gleichzeitig,
Semaphore in SubagentRunner).

## 3. Orchestrator-Agent

```markdown
---
name: research-orchestrator
mode: primary
steps: 10
toolAllow: [subagent]
---
Du bist ein Research-Orchestrator. Wenn der User mehrere Themen nennt:
1. Starte für jedes Thema einen Research-Subagent (parallel)
2. Warte auf alle Ergebnisse
3. Kombiniere und vergleiche die Ergebnisse
4. Gib eine Gesamtzusammenfassung zurück
```

## 4. Einschränkungen

| Einschränkung | Auswirkung |
|---|---|
| Depth-Limit (2) | Orchestrator → Subagent, aber kein Subagent → Subagent |
| Semaphore (4) | Max. 4 parallele Subagents pro Engine-Instanz |
| Kein persistentes Ergebnis | Ergebnisse gehen verloren wenn Session gelöscht wird |
| Kein Callback/Webhook | Kein Push bei Async-Completion; Polling oder SSE nötig |

## 5. Empfohlene Implementierung

1. Agent-Definitionen erstellen (research-agent, research-orchestrator)
2. Testen mit einem LLM-Provider (parallele Tool-Calls)
3. Optional: Callback/Webhook für async-Completion
4. Optional: Ergebnisse in DB persistieren

## 6. Was bereits implementiert ist

- SubagentTool (parallel, depth 2, semaphore 4)
- Background-Subagent (`background=true`)
- Session-Polling (`GET /api/session/{id}/messages`)
- SSE-Stream (`GET /api/event`)
- MCP-Tool `qoc_run_agent` und `qoc_run_agent_with_config`

## 7. Was fehlt

- Callback/Webhook bei Completion (2-3 Tage)
- Ergebnis-Persistierung über Session hinaus (1-2 Tage)
- Remote-Subagent via MCP (SubagentTool ist lokal)

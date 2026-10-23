# 07 – Werkzeugfamilie: Subagent (task delegation)

> Werkzeug: `subagent` (V1-Name: `task`). Delegiert eine Aufgabe an einen
> spezialisierten Agenten in einer Kind-Session.

## 1. Zweck

Ein Agent kann eine Teilaufgabe an einen spezialisierten Subagenten
delegieren. Der Subagent läuft in einer **eigenen Session** (mit `parentID`),
mit eigenem Systemprompt, eigenen Rechten, eigenem Modell und eigenem
Schrittbudget. Das Ergebnis wird als Tool-Output an den aufrufenden Agenten
zurückgegeben.

## 2. Agenten-Modi

| Modus | Bedeutung | Sichtbar in `GET /api/agent` |
|---|---|---|
| `primary` | Auswählbar durch den Nutzer | ja |
| `subagent` | Nur gerufen (nicht auswählbar) | nein |
| `all` | beides | ja |

Built-in-Subagenten:
| Agent | Modus | Rechte | Zweck |
|---|---|---|---|
| `general` | subagent | `question` + `subagent` denied | Multi-Step/parallele Arbeit |
| `explore` | subagent | `*` denied, Whitelist: `shell, grep, glob, webfetch, websearch, read` | Dateisuche |

## 3. Werkzeug-Vertrag

```
Eingabe:  { agent: AgentID, prompt: String, description?: String }
Ausgabe:  Text (Ergebnis des Subagenten) + Metadata { sessionId, agent, steps, cost, tokens }
```

### 3.1 Ausführung

```
subagent(agent, prompt, context):
    1. Prüfe Rekursions-Tiefe (max. 2 Ebenen). Wenn Tiefe > Limit → Fehler.
    2. Lade Agent-Profil (Systemprompt, Rechte, Modell, Steps).
    3. Prüfe Rechte: Agent muss `subagent` erlaubt haben (Permission.assert).
    4. Erzeuge Kind-Session (parentID = aktuelle Session).
    5. Enqueue prompt in Kind-Session (SessionService.enqueue).
    6. Führe Kind-Session aus (Coordinator.wake) → blockiert bis idle.
    7. Lese Assistant-Antwort aus Kind-Session (Conversation.messages).
    8. Bounding: Ergebnis kürzen wenn > Limit (OutputBounding).
    9. Gib Ergebnis als Tool-Output zurück.
```

### 3.2 Rekursions-Schutz

- Max. Tiefe: 2 (Parent → Child → Grandchild, nicht tiefer).
- `general` hat `subagent` denied → kann nicht weiter delegieren.
- `explore` hat `subagent` denied → kann nicht weiter delegieren.
- Tiefe wird über `parentID`-Kette geprüft.

### 3.3 Fehlerbehandlung

| Fehler | Wirkung |
|---|---|
| Unbekannter Agent | `Tool.Error` (modellseitig sichtbar) |
| Keine Permission | `PermissionDenied` (modellseitig sichtbar) |
| Rekursions-Limit | `Tool.Error` (modellseitig sichtbar) |
| Subagent scheitert | `Tool.Error` mit Subagent-Fehler (modellseitig sichtbar) |
| Timeout | `Tool.Error` (modellseitig sichtbar) |

### 3.4 Kosten und Token

- Subagent-Kosten werden zur Parent-Session addiert.
- Subagent-Token werden separat getrackt (Metadata im Tool-Output).

## 4. Agent-Scoping

Jeder Subagent bekommt:
- Eigenen Systemprompt (aus Agent-Definition).
- Eigene Rechte (aus Agent-Definition oder Defaults).
- Eigenen Tool-Snapshot (`registry.snapshot(agentPermissions)`).
- Eigenes Schrittbudget (`steps`).
- Eigenes Modell (oder Parent-Modell als Fallback).

## 5. Agent-Definition (erweitert)

Datei `<projekt>/agent/<id>.md` oder `~/.config/opencode/agent/<id>.md`:

```markdown
---
description: Prüft Änderungen, ohne sie zu bearbeiten.
mode: subagent           # primary | subagent | all
model: anthropic/claude-sonnet-4-5
steps: 16
hidden: false
permissions:
  - { action: "*",      resource: "*", effect: allow }
  - { action: "edit",   resource: "*", effect: deny }
  - { action: "subagent", resource: "*", effect: deny }
---
Du bist ein Reviewer. Lies die Änderung, bevor du urteilst.
```

## 6. Invarianten

| # | Invariante | Prüfart |
|---|---|---|
| S-01 | Ein Subagent läuft in einer eigenen Session mit `parentID`. | Integrationstest |
| S-02 | Der Subagent kann nicht tiefer als 2 Ebenen delegieren. | Unit-Test |
| S-03 | Ein Subagent mit `subagent` = deny kann nicht weiter delegieren. | Unit-Test |
| S-04 | Das Subagent-Ergebnis wird als Tool-Output an den Parent zurückgegeben. | Integrationstest |
| S-05 | Subagent-Kosten werden zur Parent-Session addiert. | Integrationstest |
| S-06 | Ein fehlgeschlagener Subagent erzeugt `Tool.Error`, nicht einen Absturz. | Integrationstest |

## 7. Best Practices

1. **Isolation:** Kein shared mutable state zwischen Parent und Child.
2. **Timeout:** Subagent-Runs haben ein Timeout (Default: 5 min).
3. **Ergebnis-Truncation:** Große Ergebnisse werden gekürzt (OutputBounding).
4. **Keine parallelen Subagents:** Sequentielle Ausführung (einfacher, weniger Ressourcenkonkurrenz).
5. **Kontext-Übergabe:** Parent übergibt relevante Dateien/Fragen im Prompt.
6. **Tool-Beschränkung:** Subagent bekommt nur Tools, die seine Permissions erlauben.
7. **Rekursions-Schutz:** Depth-Limit + `subagent`-Deny für leaf-Agents.
8. **Kosten-Tracking:** Separate Tracking, aber Attribution zur Parent-Session.

## 8. Referenzen

- Spec: `spec/permissions/03-effect-matrix.md` (`subagent`-Action, Target-Agent-ID)
- Spec: `spec/session-engine/01-types.md` (`parentID`, `forkSessionID`, `forkBoundary`)
- Spec: `spec/session-engine/04-algorithms.md` (Execution, Claim, Wiederaufnahme)
- Spec: `spec/betrieb/README.md` §3 (Agent-Modi, Baseline: ein Agent)
- Spec: `spec/tools/01-types.md` (Tool-Vertrag)
- OpenCode-Referenz: `oc/packages/core/src/session/revert.ts` (Session-Hierarchie)
- OpenCode-Referenz: `oc/packages/core/src/tool/plugin/task.ts` (V1 task tool)
# Prompt Library – `promptlib.md`

> **Zweck:** Alle Texte, die das Modell erreichen oder sein Verhalten steuern,
> an einer Stelle dokumentieren: Quelle, Trigger, Einfluss. Die Texte liegen
> als Ressourcen unter `engine/src/main/resources/prompts/` und werden über
> `PromptLoader` geladen (PR-1: Engine-Prompts als Ressourcen, keine losen
> String-Literale; Tool-Beschreibungen sind davon ausgenommen, siehe §3).
>
> **Stand:** 2026-10-25. Die Angaben sind gegen den Code geprüft. Build und
> Tests bleiben maßgeblich.

## 0. Lademechanismus

- `PromptLoader.get(id)` lädt `/prompts/<id>.txt` vom Classpath, cached das
  Ergebnis und wirft bei fehlendem Prompt eine `IllegalArgumentException`
  (Defect, kein Fallback).
- `PromptLoader.render(id, vars)` ersetzt `{key}`-Platzhalter.
- Beim Laden wird `stripTrailing()` angewendet.
- **Es gibt kein `manifest.json`, keine Version und keine Checksumme.**
  PR-1 ist damit nur teilweise erfüllt: der Identifier ist der Dateiname.
  Golden-Tests (PR-10) sichern die Texte ab.

## 1. System-Part (Level 1)

| ID | Ressource | Trigger | Einfluss |
|---|---|---|---|
| `SYS-BASE` | `base-prompt.txt` | Jeder Request ohne `agent.system` | Rolle, Regeln, Tool-Liste (`${tools}`). Definiert das Basisverhalten. |
| `SYS-GUIDE` | `sys-guide.txt` | Mindestens eines von `shell`, `write`, `edit` im Snapshot | Tool-Hinweise. **Offene Lücke:** aktuell wird die ganze Datei angehängt; die Abschnitte werden nicht pro Tool gefiltert. |
| `SYS-PROVIDER-*` | – | – | Entfallen (PRM-A/PRM-B). Es gilt `agent.system` oder `SYS-BASE`. |

## 2. Nachrichten (Level 2)

| ID | Ressource | Trigger | Einfluss |
|---|---|---|---|
| `CTX-MAX-STEPS` | `step-limit.txt` | Letzter erlaubter Schritt | Wird als **Assistant-Message** eingefügt; Tools sind für diesen Schritt gesperrt (PR-13). |
| `MODE-PLAN-ENTER` / `MODE-PLAN-LEAVE` | `mode-plan-enter.txt` / `mode-plan-leave.txt` | Agent-Modus wechselt zu/von `plan` | Transiente `ModelMessage.User` in `ContextBuilder.history`. **Nicht persistiert**, kein `SyntheticRaised`-Event. |
| `SERVER-RESTARTED` | `server-restarted.txt` | Recovery nach Prozess-Abbruch | Informiert das Modell über den Neustart (`Recovery`). |
| `TTL-MESSAGE` / `TTL-CONVERSATION` | `ttl-message.txt` / `ttl-conversation.txt` | Neue Session / Regeneration | Titel aus erster Nachricht bzw. Historie. 8000 Zeichen Kontext, 2000 erste Nachricht, 100 Zeichen Output (PR-9). |

## 3. Tool- und Struktur-Hinweise (Level 3)

| ID | Ressource / Ort | Trigger | Einfluss |
|---|---|---|---|
| `SKILL-GUIDANCE` | `skill-guidance.txt` (`SkillCatalog`) | Sichtbare Skills | `<available_skills>`-Block im Instruction-Teil. |
| `NO-SKILLS` | `no-skills.txt` (`SkillCatalog`) | Keine sichtbaren Skills | `No skills are currently available.` |
| `UNABLE-TO-LOAD-SKILL` | `unable-to-load-skill.txt` (`SkillTool`) | Unbekannte Skill-ID | Fehlertext für den Tool-Aufruf. |
| `BOUNDING-HINT` | `bounding-hint.txt` (`OutputBounding`) | Output > 2000 Zeilen oder > 50 KiB | `[showing lines 1-n of total; full output saved to path]`. |
| `MCP-INSTRUCTIONS` | `mcp-instructions.txt` (`McpInstructions`) | MCP-Server mit nicht vollständig verweigerten Tools | `<mcp_instructions>`-Block; blockierte Server fehlen vollständig. |
| `TOOL-*` | Java-Literale in `ToolDefinition` | Tool-Registrierung | **22 statische Tool-Namen** plus dynamische MCP-Tools. Teil des Modell-Contracts (PR-10). Nicht als Ressource ausgelagert. |

## 4. Compaction (Level 4)

| ID | Ressource | Trigger | Einfluss |
|---|---|---|---|
| `CTX-SUMMARY` | `ctx-summary.txt` | Compaction ohne Vorgänger | Strukturierte Zusammenfassung. |
| `CTX-SUMMARY-UPDATE` | `ctx-summary-update.txt` | Compaction mit Vorgänger (`hasPredecessor`) | Konsolidiert die bestehende Zusammenfassung; neuere Historie gewinnt (PR-8). |
| `CTX-NUDGE` | `ctx-nudge.txt` (`CtxNudge`) | Antwort verletzt das Summary-Template | Einmaliger Re-Prompt; erfordert einen Provider. |
| `CTX-CHECKPOINT` | `ctx-checkpoint.txt` (`ConversationFolding`) | Jede `CompactionMessage` | Wrapper `<conversation-checkpoint>` um Summary + Recent-Context. **Offene Lücke:** `recent` wird aktuell immer als leer übergeben. |

## 5. Regeln (PR-1…PR-13)

| # | Regel |
|---|---|
| PR-1 | Jeder Engine-Prompt ist eine Ressource mit Identifier (Dateiname). Version/Checksumme sind **nicht** implementiert. Tool-Beschreibungen sind davon ausgenommen (siehe PR-10). |
| PR-2 | System-Part: höchstens zwei Blöcke (Basis + Instruction). Leere werden entfernt. |
| PR-3 | `agent.system` ersetzt die Basis **vollständig**. |
| PR-4 | Precedence: Agent → Basis. Provider-Templates sind entfallen (PRM-A/PRM-B). |
| PR-5 | Tool-Hinweise kommen aus dem **Snapshot**, nicht aus der Registry. |
| PR-6 | Der Instruction-Block ist der einzige mutable System-Teil (cache-relevant). |
| PR-7 | Mode-Reminder sind Nachrichten, keine System-Teile. |
| PR-8 | Compaction: erst produzieren, dann aktualisieren; neuere Historie gewinnt; der Prozess wird nie erwähnt. |
| PR-9 | Titel: nur Titel, keine Tools, 8000 Zeichen Kontext, 2000 erste Nachricht, 100 Zeichen Output, Sprache der Nachricht. |
| PR-10 | Tool-Beschreibung ist Teil des Modell-Contracts; Änderungen sind bewusst und golden-getestet. |
| PR-11 | Kein Prompt enthält Secrets, Keys oder unnötige Werte. |
| PR-12 | Produkttexte (Commands, Skills) sind erweiterbar; die Engine kennt nur IDs und lädt aus dem Katalog. |
| PR-13 | Step-Limit-Prompt ist eine Assistant-Message, kein System-Part; Tools sind für diesen Schritt gesperrt. |

## 6. Code-Stellen (Ist-Stand)

| Klasse | Prompt | Status |
|---|---|---|
| `ContextBuilder` | `base-prompt`, `sys-guide`, `step-limit`, `mode-plan-*` | ✅ aus Ressourcen geladen |
| `ConversationFolding` | `ctx-checkpoint` | ✅ aus Ressource geladen; `recent` leer |
| `CompactionService` | `ctx-summary`, `ctx-summary-update` | ✅ aus Ressourcen geladen |
| `CtxNudge` | `ctx-nudge` | ✅ aus Ressource geladen |
| `SkillCatalog` | `skill-guidance`, `no-skills` | ✅ aus Ressourcen geladen |
| `SkillTool` | `unable-to-load-skill` | ✅ aus Ressource geladen |
| `OutputBounding` | `bounding-hint` | ✅ aus Ressource geladen |
| `McpInstructions` | `mcp-instructions` | ✅ aus Ressource geladen |
| `Recovery` | `server-restarted` | ✅ aus Ressource geladen |
| `TitleGenerator` | `ttl-message`, `ttl-conversation` | ✅ aus Ressourcen geladen |
| `ToolDefinition` (alle Tools) | Tool-Beschreibungen | ⚠️ Java-Literale, nicht ausgelagert |

## 7. Implementierungsstatus

### Verdrahtet

- `SYS-BASE`, `SYS-GUIDE`, `CTX-MAX-STEPS`, `MODE-PLAN-ENTER/LEAVE`
- `CTX-SUMMARY`, `CTX-SUMMARY-UPDATE`, `CTX-NUDGE`, `CTX-CHECKPOINT`
- `TTL-MESSAGE`, `TTL-CONVERSATION`
- `SKILL-GUIDANCE`, `NO-SKILLS`, `UNABLE-TO-LOAD-SKILL`
- `BOUNDING-HINT`, `MCP-INSTRUCTIONS`, `SERVER-RESTARTED`

### Bekannte Lücken

| Lücke | Detail |
|---|---|
| `SYS-GUIDE`-Filterung | Die ganze Datei wird angehängt, sobald eines von `shell`/`write`/`edit` vorhanden ist. |
| `CTX-CHECKPOINT`-Recent | `ConversationFolding` übergibt `recent=""`; der Block bleibt leer. |
| `MODE-PLAN`-Persistenz | Reminder sind transient und werden nicht als `SyntheticRaised` geloggt. |
| `TTL`-Modell | `Engine.java` erzeugt `new ModelRef(ProviderId.create(), ModelId.create())` mit zufälligen IDs; mit echtem Provider problematisch. |
| `CTX-SUMMARY-UPDATE` | Verdrahtet und golden-getestet, aber kein End-to-End-Test des Vorgängerpfads. |
| Manifest/Checksumme | Nicht implementiert (siehe §0). |
| Tool-Beschreibungen | 22 statische Tools, davon 9 golden-getestet; keine Ressourcen. |

## 8. Gold-Tests (PR-10)

- `PromptGoldenTest` (14 Tests) pinnt die zentralen Prompt-Texte.
- `ToolDescriptionGoldenTest` (9 Tests) pinnt die Beschreibungen von `read`,
  `write`, `edit`, `patch`, `glob`, `grep`, `shell`, `skill` und `websearch`.
- Noch nicht golden-getestet: die übrigen statischen Tools und dynamische
  MCP-Tools.

## 9. Pflege-Regeln

1. Neuer Prompt → Ressource unter `prompts/`, Loader-Key, Golden-Test.
2. Keine Provider-Kontrolltoken (z. B. `<|end|>`) in Prompt-Dateien.
3. Textänderungen sind Verhaltensänderungen und müssen im Golden-Test sichtbar
   werden.
4. Keine Secrets, Keys oder lokalen Pfade in Prompts.

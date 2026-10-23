# Prompt Library – `promptlib.Md`

> **Zweck:** Alle Texte, die das Modell erreichen oder sein Verhalten steuern,
> an einer Stelle dokumentieren: Quelle, Trigger, Einfluss. Die Texte selbst
> liegen jetzt als Ressourcen unter `engine/src/main/resources/prompts/` und
> werden über `PromptLoader` geladen (PR-1: kein loser String-Literal im Code).

## 1. System-Part (Level 1)

| ID | Quelle (Spec) | Java-Konstante / Loader-Key | Trigger | Einfluss |
|---|---|---|---|---|
| `SYS-BASE` | `13-internal-prompts.md` §6.1 | `PromptLoader.get("base-prompt")` (`ContextBuilder.basePrompt`) | Jeder Request ohne `agent.system` | Rolle, Regeln, Tool-Hinweise. Definiert das Verhalten des Agenten (parallelisieren, Dateipfade, Konsistenz). |
| `SYS-GUIDE` | §6.2 | `PromptLoader.get("skill-guidance")` (Header in `SkillCatalog`) | Nur für Tools im Snapshot | Tool-Hinweise (shell, write, edit). Wird zur Render-Zeit aus den verfügbaren Tools gebaut; ohne `shell` keine Shell-Hinweise. |
| `SYS-PROVIDER-*` | – | **entfallen** (PRM-A/PRM-B) | – | Provider-Templates sind gestrichen; es gilt `agent.system` oder `SYS-BASE`. |

## 2. Nachrichten (Level 2)

| ID | Quelle | Loader-Key | Trigger | Einfluss |
|---|---|---|---|---|
| `CTX-MAX-STEPS` | `04-algorithms.md` §G | `PromptLoader.get("step-limit")` (`ContextBuilder.STEP_LIMIT_PROMPT`) | Letzter erlaubter Schritt | Wird als **Assistant-Message** eingefügt, Tools werden für diesen Schritt gesperrt. Verhindert Endlosschleifen. |
| `MODE-PLAN-ENTER` / `MODE-PLAN-LEAVE` | `plugin/plan.ts` | (nicht implementiert) | Agent-Wechsel zu/von `plan` | Modus-Reminder als Nachricht, synthetisch geloggt. |
| `CTX-CHECKPOINT` | `runner/to-llm-message.ts:300-312` | (Wrapper) | Jede Compaction-Nachricht | Entscheidet, ob eine Summary als *Instruktion* oder als *Historie* gelesen wird. |
| `CTX-NUDGE` | `session/compaction.ts:178-180` | (nicht implementiert) | Antwort ohne Template | Follow-up bei Formatverletzung. |
| `TTL-MESSAGE` / `TTL-CONVERSATION` | `session/title.ts` | (nicht implementiert) | Neue Session / Regeneration | Titel aus erster Nachricht / Historie (8000 Zeichen Kontext, 2000 erste Nachricht, 100 Zeichen Output). |
| Recovery-Synthetic | `04-algorithms.md` §L | `PromptLoader.get("server-restarted")` | Recovery nach Prozess-Abbruch | Informiert das Modell über den Neustart; Claim bleibt bei Shutdown. |
| Skill-Activation | `skill.ts:36-52` | (Tool-Result, nicht Prompt) | `skill`-Tool-Aufruf | Liefert `<skill_content>` als Tool-Result. |

## 3. Tool-Beschreibungen (Level 3)

| ID | Quelle | Einfluss |
|---|---|---|
| `TOOL-*` | `tool/plugin/*.ts` | 15 Tool-Beschreibungen (read, write, edit, patch, glob, grep, shell, skill, MCP, …). Teil des Modell-Contracts; Änderung = Verhaltensänderung (PR-10). |
| `TOOL-PATCH` | `tool/patch.txt` | Patch-Format-Beschreibung. |
| `TOOL-TODOWRITE` | `tool/todowrite.txt` | Todo-Write-Beschreibung. |

## 4. Struktur-Hinweise (Level 4)

| ID | Quelle | Loader-Key | Trigger | Einfluss |
|---|---|---|---|---|
| `CTX-SUMMARY` | `session/compaction.ts:107-170` | `PromptLoader.get("ctx-summary")` | Compaction ohne Vorgänger | Strukturierte Zusammenfassung (Objective, Requirements, Decisions, Work State, Next Move, Relevant Files, Important Context). |
| `CTX-SUMMARY-UPDATE` | `session/compaction.ts:149-177` | (nicht implementiert) | Compaction mit Vorgänger | Konsolidiert bestehende Zusammenfassung; neuere Historie gewinnt. |
| `CTX-CHECKPOINT` | `runner/to-llm-message.ts:300-312` | (Wrapper) | Jede Compaction-Nachricht | Wrapper `<conversation-checkpoint>` um Summary + Recent-Context. |
| `SYS-GUIDE` (Skills) | `skill/instructions.ts` | `PromptLoader.get("skill-guidance")` | Sichtbare Skills | `<available_skills>`-Block im Instruction-Teil. |
| `MCP-INSTRUCTIONS` | `tools/04-mcp.md` §7 | (Template in `McpInstructions`) | MCP-Server mit nicht komplett verweigerten Tools | `<mcp_instructions>`-Block; blockierte Server sind vollständig absent. |
| Bounding-Hint | `tools/02-filesystem.md` §9 | `PromptLoader.render("bounding-hint", …)` | Output > 2000 Zeilen / 50 KiB | `[showing lines 1-n of total; full output saved to path]`. |
| `NO-SKILLS` | `skill/instructions.ts:31` | `PromptLoader.get("no-skills")` | Keine sichtbaren Skills | `No skills are currently available.` |
| `UNABLE-TO-LOAD-SKILL` | `tool/plugin/skill.ts:30` | `PromptLoader.render("unable-to-load-skill", …)` | Unbekannte Skill-ID | `Unable to load skill <id>`. |
| `SERVER-RESTARTED` | `04-algorithms.md` §L | `PromptLoader.get("server-restarted")` | Recovery nach Prozess-Abbruch | `The server was restarted. Continuing.` |

## 5. Regeln (PR-1…PR-13)

| # | Regel |
|---|---|
| PR-1 | Jeder Prompt hat Identifier, Version, Checksumme. Kein loser String-Literal im Code. |
| PR-2 | System-Part: höchstens zwei Blöcke (Basis + Instruction). Leere werden entfernt. |
| PR-3 | `agent.system` ersetzt die Basis **vollständig**. |
| PR-4 | Precedence: Agent → Provider → Basis. |
| PR-5 | Tool-Hints aus dem **Snapshot**, nicht aus der Registry. |
| PR-6 | Instruction-Block ist der einzige mutable System-Teil (cache-relevant). |
| PR-7 | Mode-Reminder sind Nachrichten, nicht System-Teile, synthetisch geloggt. |
| PR-8 | Compaction: erst produziert, dann aktualisiert; neuere Historie gewinnt; Prozess wird nie erwähnt. |
| PR-9 | Title: nur Titel, keine Tools, 8000 Zeichen Kontext, 2000 erste Nachricht, 100 Zeichen Output, Sprache der Nachricht. |
| PR-10 | Tool-Beschreibung ist Teil des Modell-Contracts (versioniert, golden-getestet). |
| PR-11 | Kein Prompt enthält Secrets, Keys oder unnötige Werte. |
| PR-12 | Produkttexte (Commands, Skills) sind erweiterbar; Engine kennt nur IDs und lädt aus dem Katalog. |
| PR-13 | Step-Limit-Prompt ist eine Assistant-Message, nicht System-Part; Tools werden für diesen Schritt gesperrt. |

## 6. Code-Stellen (vor Refactoring)

| Datei | Prompt | Status |
|---|---|---|
| `CompactionService.java` | `CTX_SUMMARY` | ✅ ausgelagert nach `prompts/ctx-summary.txt` |
| `ContextBuilder.java` | `STEP_LIMIT_PROMPT` | ✅ ausgelagert nach `prompts/step-limit.txt` |
| `ContextBuilder.java` | `basePrompt` | ✅ ausgelagert nach `prompts/base-prompt.txt` |
| `SkillCatalog.java` | `NO_SKILLS` + Skill-Guidance-Block | ✅ ausgelagert nach `prompts/no-skills.txt` + `prompts/skill-guidance.txt` |
| `OutputBounding.java` | Bounding-Hint | ✅ ausgelagert nach `prompts/bounding-hint.txt` |
| `Recovery.java` | Server-restarted-Nachricht | ✅ ausgelagert nach `prompts/server-restarted.txt` |
| `SkillTool.java` | Unable-to-load-skill | ✅ ausgelagert nach `prompts/unable-to-load-skill.txt` |
| `McpInstructions.java` | MCP-Instructions-Block | ✅ ausgelagert nach `prompts/mcp-instructions.txt` |

## 7. Prompt-Manifest (PR-1)

Jeder Prompt hat eine ID, Version und Checksumme. Die Manifest-Datei
`engine/src/main/resources/prompts/manifest.json` listet alle Prompts mit
ihrem Dateipfad. Ein fehlender Prompt ist ein Startfehler (Defect), kein
Fallback.

## 8. Implementierungsstatus (Stand nach M14 + Prompt-Logik)

### Implementiert und verdrahtet
- `CTX-SUMMARY` – CompactionService (vollständiger Fluss)
- `CTX-SUMMARY-UPDATE` – CompactionService (Vorgänger-Erkennung, PR-8)
- `CTX-NUDGE` – CompactionService (Format-Prüfung + Re-Prompt)
- `CTX-CHECKPOINT` – ContextBuilder (CompactionMessage → `<conversation-checkpoint>`)
- `SYS-BASE` – ContextBuilder.basePrompt (aus `prompts/base-prompt.txt`)
- `SYS-GUIDE` – ContextBuilder (tool-abhängige Hinweise aus Snapshot)
- `STEP_LIMIT` (CTX-MAX-STEPS) – ContextBuilder (Assistant-Message, Tools gesperrt)
- `MODE-PLAN-ENTER/LEAVE` – ContextBuilder (Insert vor letzter User-Message)
- `TTL-MESSAGE/TTL-CONVERSATION` – TitleGenerator + `POST /api/session/{id}/title`
- `NO-SKILLS` – SkillCatalog
- `SERVER-RESTARTED` – Recovery
- `UNABLE-TO-LOAD-SKILL` – SkillTool
- `BOUNDING-HINT` – OutputBounding
- `MCP-INSTRUCTIONS` – McpInstructions
- `SKILL-GUIDANCE` – SkillCatalog

### Verdrahtet (Stand nach diesem Commit)
- `MODE-PLAN`: Agent-Modus-Feld (`planMode`) auto-detected aus Name oder
  verweigerter `edit`-Permission. Synthetic `SyntheticRaised`-Event wird bei
  Plan-Modus protokolliert. Standard-Catalogue enthält jetzt einen `plan`-Agent.
- `TTL`: Titel wird nach dem ersten erfolgreichen Prompt automatisch generiert
  und in der Session gespeichert (`generateTitle` + `SessionRenamed`-Event).
- `CTX-NUDGE`: In `CompactionService.compact` verdrahtet – prüft Format und
  re-prompted mit Nudge-Prompt (erfordert Provider).
- `CTX-CHECKPOINT`: in `ContextBuilder.append` für `CompactionMessage`.
- `CTX-SUMMARY-UPDATE`: Logik vorhanden (Vorgänger-Erkennung).

### Noch nicht verdrahtet
- `CTX-CHECKPOINT` in einer dedizierten Message-Folding-Pipeline
  (Conversation-Projektion fehlt – aktuell nur im ContextBuilder).
- `MODE-PLAN`-Reminder als synthetische Nachrichten in der Conversation-
  Projektion (aktuell nur im ContextBuilder eingefügt).
- `TTL` nutzt einen zufälligen `ModelRef` (funktioniert mit scripted provider,
  nicht mit echtem Provider).
- `CTX-NUDGE` Re-Prompt erfordert einen Provider (funktioniert nur mit echtem
  Provider).
- `CTX-SUMMARY-UPDATE` noch nicht end-to-end getestet.

### Gold-Tests (PR-10)
- `PromptGoldenTest` (14 Tests) pinnt die wichtigsten Prompt-Texte.
- Tool-Beschreibungen (15 Tools) sind noch nicht golden-getestet.

### SYS-BASE
- Text ist produkt-spezifisch (OpenCode-Branding) und kann ersetzt werden,
  solange PR-2…PR-13 eingehalten werden.

### Gold-Tests (PR-10)
- `PromptGoldenTest` (14 Tests) pinnt die wichtigsten Prompt-Texte.
- Tool-Beschreibungen (15 Tools) sind noch nicht golden-getestet.

### SYS-BASE
- Text ist produkt-spezifisch (OpenCode-Branding) und kann ersetzt werden,
  solange PR-2…PR-13 eingehalten werden.
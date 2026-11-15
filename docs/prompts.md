# Prompt-Dateien der Engine

> **Zweck:** Überblick über alle Prompt-Ressourcen (`engine/src/main/resources/prompts/`),
> ihre Aufgabe, Trigger und Einfluss – plus Analyse, wie sie weniger
> entwicklerspezifisch und dynamischer für verschiedene Zwecke gebaut werden können.
>
> Detail-Dokumentation: `promptlib.md` (Root, gegen Code geprüft) und
> `docs/spec/session-engine/13-internal-prompts.md`.

---

## 1. Lademechanismus

- `PromptLoader.get(id)` lädt `/prompts/<id>.txt` vom Classpath, cached das
  Ergebnis und wirft bei fehlendem Prompt eine `IllegalArgumentException`
  (Defect, kein Fallback).
- `PromptLoader.render(id, vars)` ersetzt `{key}`-Platzhalter.
- Beim Laden wird `stripTrailing()` angewendet.
- Es gibt kein `manifest.json`, keine Version und keine Checksumme.
  Der Identifier ist der Dateiname; Golden-Tests sichern die Texte ab.

---

## 2. System-Part (Level 1)

| Datei | ID | Trigger | Aufgabe |
|---|---|---|---|
| `base-prompt.txt` | `SYS-BASE` | Jeder Request ohne `agent.system` | Basis-Systemprompt: Rolle („OpenCode session engine"), Regeln, Tool-Liste via `${tools}`. Definiert das Grundverhalten. |
| `sys-guide.txt` | `SYS-GUIDE` | Mindestens eines von `shell`, `write`, `edit` im Snapshot | Tool-Hinweise. Enthält Marker-Kommentare (`# only if "shell" is available`), wird aber **als Ganzes angehängt** – die Abschnitte werden nicht pro Tool gefiltert (bekannte Lücke). |

Hinweis: `agent.system` ersetzt die Basis vollständig (PR-3/PR-4).
Provider-Templates sind entfallen (PRM-A/PRM-B).

---

## 3. Nachrichten (Level 2)

| Datei | ID | Trigger | Aufgabe |
|---|---|---|---|
| `step-limit.txt` | `CTX-MAX-STEPS` | Letzter erlaubter Schritt | Assistant-Message; Tools sind für diesen Schritt gesperrt (PR-13). Fordert Textabschluss: Zusammenfassung der geleisteten Arbeit, offene Punkte, nächste Empfehlungen. Keine Tool-Aufrufe. |
| `mode-plan-enter.txt` | – | Agent-Modus wechselt zu `plan` | Reminder: nicht editieren, Plan nach `~/.opencode/plan/<name>.md` schreiben, nur Pfade aus der Plan-Datei dürfen editiert werden. Transient, nicht persistiert, kein `SyntheticRaised`-Event. |
| `mode-plan-leave.txt` | – | Agent-Modus verlässt `plan` | Reminder: normales Editieren wieder erlaubt. |
| `server-restarted.txt` | – | Recovery nach Prozess-Abbruch | Synthetische Nachricht (`SyntheticRaised` bzw. `runPrompt`), informiert das Modell über den Neustart. |
| `ttl-message.txt` | – | Neue Session | Titel-Generierung aus der **ersten Nachricht** (`{text}`); Antwort = nur der Titel (2000 Zeichen Input, 100 Zeichen Output). |
| `ttl-conversation.txt` | – | Regeneration | Titel-Generierung aus der Historie (`{text}`, 8000-Zeichen-Kontext), ebenfalls „Reply with only the title". |

---

## 4. Tool- und Struktur-Hinweise (Level 3)

| Datei | ID | Trigger | Aufgabe |
|---|---|---|---|
| `skill-guidance.txt` | `SKILL-GUIDANCE` | Sichtbare Skills | Erklärung des Skill-Mechanismus im `<available_skills>`-Block: Skill-Tool laden, direkte User-Invokation erscheint als `<skill_content>`-Block, keine Doppel-Invokation. |
| `no-skills.txt` | `NO-SKILLS` | Keine sichtbaren Skills | Fallback-Text: `No skills are currently available.` |
| `unable-to-load-skill.txt` | – | Unbekannte Skill-ID | Fehlermeldung des Skill-Tools: `Unable to load skill {id}`. |
| `bounding-hint.txt` | – | Output > 2000 Zeilen oder > 50 KiB | Truncation-Hinweis: `[showing lines 1-{shown} of {total}; full output saved to {path}]`. |
| `mcp-instructions.txt` | – | MCP-Server mit nicht vollständig verweigerten Tools | Wrapper `<mcp_instructions>{servers}</mcp_instructions>`; blockierte Server fehlen vollständig. |

Nicht als Ressourcen ausgelagert: die **22 statischen Tool-Beschreibungen**
(Java-Literale in `ToolDefinition`). Sie sind Teil des Modell-Contracts
(PR-10), 9 davon golden-getestet.

---

## 5. Compaction (Level 4)

| Datei | ID | Trigger | Aufgabe |
|---|---|---|---|
| `ctx-summary.txt` | `CTX-SUMMARY` | Compaction ohne Vorgänger | Strukturierte Zusammenfassung nach festem Template (Objective, Requirements, Decisions, Work State, Next Move, Relevant Files, Important Context) inkl. Regeln: Kürze, exakte Pfade/Bezeichner, nur offene User-Fragen tragen, Prozess/Compaction nicht erwähnen, keine Tools. |
| `ctx-summary-update.txt` | `CTX-SUMMARY-UPDATE` | Compaction mit Vorgänger (`hasPredecessor`) | Konsolidiert die bestehende Zusammenfassung: neuere Historie gewinnt, veraltete/unrelevante Teile entfernen, Wrapper-Tags nicht reproduzieren (PR-8). |
| `ctx-nudge.txt` | `CTX-NUDGE` | Antwort verletzt das Summary-Template | Einmaliger Re-Prompt: keine Tools, nur Summary mit exakten Sektionsüberschriften. Erfordert einen Provider. |
| `ctx-checkpoint.txt` | – | Jede `CompactionMessage` | Wrapper `<conversation-checkpoint>` mit `<summary>` und `<recent-context>`. Lücke: `recent` wird aktuell immer als leerer String übergeben. |

---

## 6. Verdrahtung (Code-Stellen)

| Klasse | Prompt | Status |
|---|---|---|
| `ContextBuilder` | `base-prompt`, `sys-guide`, `step-limit`, `mode-plan-*`, `ctx-checkpoint` | ✅ aus Ressourcen geladen; `recent` leer |
| `ConversationFolding` | `ctx-checkpoint` | ✅ aus Ressource geladen; `recent` leer |
| `CompactionService` | `ctx-summary`, `ctx-summary-update` | ✅ aus Ressourcen geladen |
| `CtxNudge` | `ctx-nudge` | ✅ aus Ressource geladen |
| `SkillCatalog` | `skill-guidance`, `no-skills` | ✅ aus Ressourcen geladen |
| `SkillTool` | `unable-to-load-skill` | ✅ aus Ressource geladen |
| `OutputBounding` | `bounding-hint` | ✅ aus Ressource geladen |
| `McpInstructions` | `mcp-instructions` | ✅ aus Ressource geladen |
| `Recovery` / `QocEngine` | `server-restarted` | ✅ aus Ressource geladen |
| `TitleGenerator` | `ttl-message`, `ttl-conversation` | ✅ aus Ressourcen geladen |
| `ToolDefinition` (alle Tools) | Tool-Beschreibungen | ⚠️ Java-Literale, nicht ausgelagert |

---

## 7. Bekannte Lücken

| Lücke | Detail |
|---|---|
| `SYS-GUIDE`-Filterung | Ganze Datei wird angehängt, sobald eines von `shell`/`write`/`edit` vorhanden ist; die `# only if`-Marker landen im Prompt. |
| `CTX-CHECKPOINT`-Recent | `ConversationFolding` übergibt `recent=""`; der Block bleibt leer. |
| `MODE-PLAN`-Persistenz | Reminder sind transient und werden nicht als `SyntheticRaised` geloggt. |
| `TTL`-Modell | `Engine.java` erzeugt `ModelRef` mit zufälligen IDs; mit echtem Provider problematisch. |
| `CTX-SUMMARY-UPDATE` | Verdrahtet und golden-getestet, aber kein End-to-End-Test des Vorgängerpfads. |
| Manifest/Checksumme | Nicht implementiert (siehe §1). |
| Tool-Beschreibungen | 22 statische Tools, davon 9 golden-getestet; keine Ressourcen. |

---

## 8. Dynamisierung: weniger entwicklerspezifisch, anpassbar für verschiedene Zwecke

Aktuelle Einschränkungen: Texte sind hart kodiert, englischsprachig, auf eine
konkrete Produktidentität („OpenCode", `~/.opencode/plan/…`) und feste
Sprach-/Regelkonstanten zugeschnitten.

### 8.1 Produktidentität, Pfade und Limits als Variablen

- `base-prompt.txt`: „You are the OpenCode session engine" → `{product}` oder
  `{role}` aus Konfiguration (Persona/Deployment-abhängig).
- `mode-plan-enter.txt`: hart kodiertes `~/.opencode/plan/<name>.md` →
  `{planDir}` aus Config (workspace-/produktabhängig).
- Summary-/TTL-Limits (8000/2000/100 Zeichen, max. 15 Dateien) als
  `{maxChars}`-Variablen statt fest im Text – pro Deployment justierbar.

### 8.2 Layered Loader (Overrides statt nur Classpath)

Fallback-Kette für `PromptLoader`:

```
CLI-Flag → Config-Verzeichnis (~/.<app>/prompts/) → Workspace-Override (.prompts/) → Classpath-Default
```

Damit können Nutzer/Deployments Prompts überschreiben, ohne die Engine neu zu
bauen; die Engine kennt weiterhin nur IDs (PR-12 konsequent fortgeführt).
Dazu `manifest.json` mit ID → Version → Checksumme, um Overrides zu
validieren und Abweichungen erkennbar zu machen.

### 8.3 Sprach- und Zielgruppen-Adaption

- Alle Engine-Prompts sind Englisch; ein `lang`-Rendering-Parameter
  (`ctx-summary.en.txt` / `ctx-summary.de.txt` oder Übersetzungstabelle pro
  Key) macht Compaction-, Nudge- und Step-Limit-Texte sprachabhängig ohne
  Codeänderung.
- Alternativ „Antworte in der Sprache des Nutzers" als Variable.

### 8.4 Komposition statt Monolithen

- `sys-guide.txt` in Fragmente zerlegen (`sys-guide/shell.txt`,
  `sys-guide/write.txt`, …), die der Loader pro vorhandenem Tool
  zusammensetzt – die Marker-Lücke entfällt strukturell.
- `ctx-summary.txt`: Template (Sektionen) und Regeln (Kompaktheit, Pfade,
  Verschwiegenheit) als getrennte Fragmente, je nach Anwendungsfall
  zusammensetzbar oder selektiv überschreibbar.
- `step-limit.txt` mischt Verbotsregeln, Pflichtstruktur und Eskalationssprache
  („CRITICAL"); als Fragmente ist die Tonalität pro Deployment austauschbar,
  ohne die Vertragssemantik (keine Tools, Text-Abschluss) zu gefährden.

### 8.5 Profile / PromptContext

Ein zentrales `PromptContext` (Produktname, Toolset, Plan-Modus-Verhalten,
Sprache, Limits, Zielgruppe) befüllt `PromptLoader.render()`. Jede Datei wird
damit ein neutrales Template: dasselbe `ctx-summary` dient der Coding-Engine,
einem Support-Bot oder einem Recherche-Agenten – je nach Profil.

### 8.6 Struktur-Absicherung beibehalten

Template-Texte wie das Summary-Format sind ein **Vertrag** zwischen Engine
(Post-Processing, `CtxNudge`-Erkennung anhand Sektionsüberschriften) und
Modell. Regeln für jede Dynamisierung:

- Golden-Tests (`PromptGoldenTest`, `ToolDescriptionGoldenTest`) auf die
  *gerenderte* Struktur (Sektionsnamen, Wrapper-Tags) pinnen, nicht auf
  exakte Bytes.
- Overrides per Checksumme/Manifest validieren.
- Verhaltenskritische Prompts (`step-limit`, `ctx-nudge`) nur in semantisch
  äquivalenten Varianten zulassen.
- Keine Provider-Kontrolltoken (z. B. `<|end|>`) in Prompt-Dateien (PR-1…PR-13,
  Pflege-Regeln in `promptlib.md` §9).

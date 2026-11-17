# Plan: Tool-Call-Leak erkennen, reparieren und retryen

**Status:** Implementiert — 2026-11-17
**Bezug:** `docs/spec/provider/03-classification.md`, `docs/spec/session-engine/`,
`docs/plans/strict-structured-outputs.md`
**Auslöser:** DB-Analyse `session_message` seit `2026-11-16 00:00 UTC`

---

## 1. Befund

In der Postgres-Historie (`qoc.session_message`) liegt mindestens ein
Assistenten-Turn, in dem der Provider native Tool-Call-Markup als **normalen
Text** zurückgegeben hat, statt `tool_calls` zu liefern:

- Session: `ses_825b704fbbf9859f87ae3e6499c06f6b`
- Seq: `40`
- Länge: 3342 Zeichen
- Finish: `NORMAL`
- Muster: `<｜｜DSML｜｜ calls>` / `<｜｜DSML｜｜ invoke name="shell">` /
  `<｜｜DSML｜｜ parameter ...>` mit zwei verlorenen `shell`-Aufrufen.

Das ist kein gültiger Endtext. Der Turn wurde trotzdem als Assistenten-Nachricht
persistiert, weil `Step` den Leak nur bei deaktivierten Tools (`Choice.None`)
prüfte. Zusätzlich normalisierte der Detektor doppelte Fullwidth-Bars
(`｜｜`) nicht zu einem einzelnen `|`, sodass selbst die vorhandene Prüfung den
echten Payload verfehlt hätte.

Weitere Randbedingungen aus der Analyse:

- 358 Assistenten-Nachrichten, davon 10 ohne Tool-Nutzung; nur ein DSML-Leak.
- 213 von 608 Tool-Aufrufen waren `permission_denied` (kein Leak, aber
  Retry-/UX-relevant).
- Eine verwaiste Session (`ses_544a19e56ba19e2a4f544a8744dbe7db`, Prompt `ls`)
  endete ohne Assistenten-Schritt.

## 2. Zielbild

Sechs Maßnahmen, in dieser Reihenfolge:

1. **Detektor härten** — echte Leak-Marker zuverlässig erkennen, unabhängig von
   Tool-Freigabe.
2. **Klassifikation** — eigener, typisierter Fehler `TOOL_CALL_LEAK`, der
   retrybar ist.
3. **Engine-Retry** — Retry-Entscheidung über die Fehlermatrix (nicht über
   Meldungstext), Repair-Hint im Systemprompt, begrenzte Leak-Retries.
4. **Provider-Signal** — `finish_reason=tool_calls` ohne geparste Tool-Calls
   früh als Fehler erkennen.
5. **Salvage** — wohlgeformte DSML-Blöcke in echte Tool-Calls umwandeln, statt
   den Turn zu verlieren.
6. **Observability/Audit** — Logging, Live-Status und SQL-Audit-Job.

## 3. Punkt 1 — Detektor härten

**Datei:** `engine/.../core/eval/ToolCallLeak.java`

- Normalisierung:
  - `U+FF5C` (`｜`), `U+2502` (`│`), `U+01C0` (`ǀ`) → `|`
  - `U+2581` (`▁`) → `_`
  - Läufe von `|` auf ein `|` reduzieren
  - case-insensitive vergleichen
- Marker: `<|dsml|`, `</|dsml|`, `<|tool`, `[tool_calls]`
- Signaturprüfung: Marker allein reicht nicht (Doku-Zitat), zusätzlich muss
  `invoke`, `parameter`, `calls`, `call` oder `[tool_calls]` vorkommen.
- `find(text)` liefert `Finding(startIndex, endIndex, marker)` für Cleaning und
  Salvage; `endIndex` umfasst den schließenden DSML-Block, sonst Textende.

**Datei:** `engine/.../runtime/step/Step.java`

- Leak-Prüfung nicht mehr an `toolsDisabled(prepared)` koppeln.
- Nur prüfen, wenn keine strukturierten Tool-Calls vorliegen.
- Leak → `TOOL_CALL_LEAK` statt `UNKNOWN`.
- Salvage versuchen (Punkt 5); wenn nicht möglich → `StepResult.Failed`.
- Bei erfolgreichem Salvage: Text-Events durch bereinigten Text ersetzen und
  die wiederhergestellten Tool-Calls wie normale Calls ausführen.

**Tests:** `ToolCallLeakTest` (Double-Bar, Box-Drawing, `▁`, split, False
Positive), `StepTest` (Tools aktiv + Leak, Salvage).

## 4. Punkt 2 — Klassifikation

**Dateien:**

- `engine/.../core/contract/provider/InvalidOutputClassification.java`
  → `TOOL_CALL_LEAK` ergänzen.
- `engine/.../core/eval/ProviderClassifier.java`
  → `TOOL_CALL_LEAK` ist retrybar.
- `docs/spec/provider/03-classification.md` ist die fachliche Quelle; die
  Erweiterung wird dort als offener Spec-Nachtrag vermerkt (kein stilles
  Überschreiben).

**Tests:** `ProviderClassifierTest` (retrybar / non-retryable Matrix).

## 5. Punkt 3 — Engine-Retry und Repair-Hint

**Datei:** `engine/.../runtime/QocEngine.java`

- `StepResult.Failed` wird über `ProviderClassifier.isRetryable(kind)`
  entschieden; die bisherige Textklassifikation (`IllegalStateException` →
  `CORRECTABLE`) entfällt für Step-Fehler.
- `TOOL_CALL_LEAK` bekommt ein eigenes Budget:
  `LEAK_RETRY_MAX = 1` (ein Reparaturversuch nach dem Erstauftritt).
- Bei Leak:
  - `LEAK_REPAIR_HINT` wird dem nächsten `Prepared` via `withSystem(...)`
    hinzugefügt (einmalig).
  - Delay = 0 (kein Backoff, kein Rate-Limit).
  - Live-Status: `[Retry] Tool-call markup detected ...`.
  - Log mit Session, Step und Aktion.
- Der forcierte finale Schritt (Tools deaktiviert) wird ebenfalls auf
  `StepResult.Failed` geprüft und bei Leak genau einmal mit Hint wiederholt;
  danach typisierter Engine-Fehler.

**Tests:** `QocEngine`-Retry ist über die Matrix unit-testbar; ein
Integrationstest mit Fake-Provider prüft: erster Step Leak → zweiter Step
sieht Hint → Turn wird `COMPLETED`, kein Leak-Text persistiert.

## 6. Punkt 4 — Provider-Signal

**Datei:** `engine/.../runtime/provider/client/OpenAiEventTranslator.java`

- Rohen `finish_reason` merken.
- In `finish()`: `finish_reason=tool_calls` **ohne** geparste Tool-Calls →
  `ProviderFailed(InvalidOutput(TOOL_CALL_LEAK))` statt `Finished`.
- Text-Leaks mit `finish_reason=stop` bleiben Aufgabe von `Step` (dort ist
  Salvage möglich).

**Tests:** `OpenAiEventTranslatorTest` (Mismatch → `ProviderFailed`, normaler
Tool-Call bleibt `Finished`).

## 7. Punkt 5 — Salvage-Parser

**Datei:** `engine/.../core/eval/ToolCallLeakParser.java` (neu)

- Parst DSML `invoke`/`parameter`-Blöcke (Fullwidth-Bars, `▁`, Whitespace).
- `SalvagedCall(String name, JsonValue input)`.
- `string="true"` → String; sonst JSON parsen, Fallback String.
- XML-Entities (`&quot;`, `&amp;`, `&lt;`, `&gt;`) auflösen.
- Grenzen: max. 8 Calls, max. 100k Zeichen pro Parameter.
- `Step` akzeptiert Salvage nur, wenn alle Namen in `prepared.tools()`
  vorhanden sind und Tools nicht deaktiviert sind. Sonst Retry-Pfad.
- Recovered Calls werden live als `ToolInputStarted` / `ToolInputEnded` /
  `ToolCallRequested` publiziert.

**Tests:** `ToolCallLeakParserTest` mit dem echten 2-Call-Payload,
Malformed-Input, String-/JSON-Parameter.

## 8. Punkt 6 — Observability und Audit

- `Step`: `WARNING`-Log bei Erkennung/Salvage.
- `QocEngine`: `WARNING`-Log bei Retry/Endfehler, Live-Statuszeile.
- `scripts/find-tool-call-leaks.sql`: findet Assistenten-Nachrichten ohne
  Tool-Card, deren Text DSML-/Tool-Markup enthält (Fullwidth-Bars tolerant),
  inkl. Session, Seq, Zeit, Länge, Preview.
- Optionaler Betriebs-Hinweis: täglicher Lauf gegen `qoc.session_message`.

## 9. Akzeptanzkriterien

- `mvn test` in `qoc/` grün.
- Der echte 3342-Zeichen-Payload wird erkannt und in zwei `shell`-Calls
  zerlegt.
- Ein Leak mit aktivierten Tools erzeugt `TOOL_CALL_LEAK` (kein Endtext).
- Ein Leak führt zu genau einem Reparatur-Retry; danach typisierter Fehler.
- `finish_reason=tool_calls` ohne Calls erzeugt `TOOL_CALL_LEAK`.
- Audit-SQL findet den historischen Leak.
- Keine Änderung an `docs/`-Spec ohne separaten Auftrag (Ausnahme: Hinweis auf
  offenen Spec-Nachtrag).

## 10. Verifikation

- `mvn -pl engine test` → **BUILD SUCCESS** (1140+ Tests, inkl. neuer
  Leak-, Parser-, Step-, Translator- und Engine-Retry-Tests).
- `mvn -pl engine clean test` → **BUILD SUCCESS**.
- `mvn -DskipTests compile` (gesamter Reaktor) → **BUILD SUCCESS**.
- `mvn test` → **BUILD SUCCESS** (alle Module: engine, workflow-runner, quarkus, cli).
- Audit-SQL `scripts/find-tool-call-leaks.sql` findet den Produktions-Leak
  `ses_825b704fbbf9859f87ae3e6499c06f6b`, Seq `40`, 3342 Zeichen.
- Neue Tests:
  - `ToolCallLeakTest` — Double-Bar, Box-Drawing, `▁`, Produktions-Payload,
    False Positive.
  - `ToolCallLeakParserTest` — 2-Call-Produktions-Payload, JSON-/String-
    Parameter, Entities, Malformed, Cap.
  - `StepTest` — Leak mit aktiven Tools, unbekanntes Tool, erfolgreicher
    Salvage.
  - `OpenAiEventTranslatorTest` — `tool_calls` ohne strukturierten Call.
  - `QocEngineLeakRetryTest` — Repair-Hint, kein Leak-Text im Ergebnis,
    Retry-Budget.

## 11. Risiken und Nicht-Ziele

- **Risiko False Positive:** Text, der DSML nur zitiert, könnte einen Retry
  auslösen. Mitigation: Signaturprüfung (Marker + `invoke`/`parameter`/`calls`).
- **Risiko Salvage:** Ein falsch geparster Call könnte einen unerwünschten
  Tool-Aufruf auslösen. Mitigation: Namensprüfung gegen `prepared.tools()`,
  Permission-Gate bleibt aktiv, max. 8 Calls.
- **Nicht-Ziel:** Beliebiges Provider-Markup (z. B. Llama-Sonderformen)
  salvage zu unterstützen. Nur die beobachteten DSML- und Tool-Marker.
- **Nicht-Ziel:** Strict Structured Outputs (Phase 3); siehe
  `docs/plans/strict-structured-outputs.md`.

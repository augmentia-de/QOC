# UI-Workflow-Editor-Tests — nutzerzentrierter Testplan

**Status:** Plan (2026-11-18)
**Bezug:** `reactflow/src/EditorPage.tsx`, `reactflow/src/pages/Workflows.tsx`, Workflow-API (`/api/workflows`), Playwright
**Nicht Teil dieses Plans:** Auth/RBAC (siehe `ui-security-tests.md`), Chat-UI, Runs-/Observe-Seiten.

---

## 1. Testhaltung: wie ein Nutzer, nicht wie das DOM

Der Test beschreibt, **was ein Nutzer sieht und tut**, nicht wie die Oberfläche
intern gebaut ist. Daraus folgen drei Regeln:

1. **Schwarzer Kasten.** Geprüft wird nur, was sichtbar ist: Nodes auf der
   Fläche, Beschriftungen, Dialoge, Statusmeldungen, Ergebnislisten.
2. **Nutzer findet Elemente über Namen und Rollen**, nicht über CSS-Klassen.
   Ein Button heißt „Speichern", ein Feld heißt „Workflow-Name".
3. **Jede Journey ist auch manuell nachvollziehbar.** Wer die Checkliste in
   Abschnitt 4 abarbeitet, testet denselben Ablauf ohne Playwright-Kenntnisse.

Automatisierung ist die Wiederholung der manuellen Prüfung — kein Ersatz dafür.

---

## 2. Was der App fehlt, damit Nutzer (und Tests) sie prüfen können

Diese Punkte sind die eigentliche Vorarbeit. Ohne sie bleibt jeder Test brüchig.

| # | Maßnahme | Warum sie hilft | Aufwand |
|---|---|---|---|
| 1 | `alert()`/`confirm()` durch Toast bzw. Inline-Status ersetzen | Ein Nutzer sieht „Gespeichert"/„Fehler" dauerhaft; der Test kann dieselbe Meldung lesen, statt einen blockierenden Browser-Dialog abzufangen | mittel |
| 2 | Bedienelemente mit Rollen und Namen versehen (`<button>`, `aria-label`, `<label for>`) | `getByRole`/`getByLabel` funktioniert dann für Screenreader **und** Playwright; Icon-Buttons werden überhaupt erst ansprechbar | klein |
| 3 | Validierungsfehler inline anzeigen (Liste im Panel, Marker am Node) | Nutzer erkennt, *was* falsch ist; Test prüft Text statt `alert`-Inhalt | mittel |
| 4 | Speicherstatus sichtbar halten („Gespeichert 12:34", „Nicht gespeichert") | Nutzer weiß, ob er gefahrlos navigieren kann; Test hat ein stabiles Signal für Persistenz | klein |
| 5 | Error Boundary um den Editor | Backend-Ausfall erzeugt eine Meldung statt weißer Seite | klein |
| 6 | Einstieg „Neu (Start+End)" und „Beispiel-Workflow" | Ein Nutzer kann ohne API/Postman starten; der Test muss keine Fixtures voraussetzen | klein |
| 7 | Testprofil mit deterministischem Provider (`ENGINE_PROVIDER_SCRIPTED=true`) | „Ausführen" liefert eine feste Antwort; Run-Tests werden reproduzierbar | klein |
| 8 | Animationen respektieren `prefers-reduced-motion`, Auto-Layout deterministisch | Weniger Timing-Flakes beim Ziehen und Verbinden | klein |
| 9 | `data-testid` **nur** für Canvas-Nodes und Handles | Dort gibt es keine semantische Rolle; überall sonst bleiben Rollen/Namen die erste Wahl | klein |

Punkt 1 und 2 sind die wichtigsten: Sie machen die Oberfläche sowohl
benutzbarer als auch testbarer — derselbe Aufwand, doppelter Nutzen.

---

## 3. Selektionsregeln (Priorität von oben nach unten)

| Priorität | Selektor | Beispiel |
|---|---|---|
| 1 | Rolle + Name | `getByRole('button', { name: 'Speichern' })` |
| 2 | Label | `getByLabel('Workflow-Name')` |
| 3 | Sichtbarer Text | `getByText('Workflow ist gültig')` |
| 4 | `data-testid` | `getByTestId('node-start-1')` |
| 5 | CSS (nur Zählen/Struktur) | `.react-flow__edge` |

`npx playwright codegen <url>` erzeugt genau diese Nutzer-Selektoren und ist
das praktische Werkzeug, um zu prüfen, ob die Oberfläche „auffindbar" ist.

---

## 4. Manueller Testdurchlauf (Checkliste, ca. 10 Minuten)

Voraussetzung: App läuft, ein Workflow ist ausgewählt oder „Neu" geklickt.
Jeder Punkt gilt als bestanden, wenn das **sichtbare Ergebnis** eintritt.

- [ ] **M1 — Leerstart:** „Neu (Start+End)" → Fläche zeigt genau zwei Nodes,
      „Start" und „Ende", verbunden durch eine Kante.
- [ ] **M2 — Node hinzufügen:** In der Palette „Agent" wählen → ein Node
      erscheint, das Eigenschaften-Panel öffnet sich und zeigt die Node-ID.
- [ ] **M3 — Verbinden:** Vom Ausgang von „Start" zum Eingang des neuen Nodes
      ziehen → eine zweite Kante erscheint, ohne Fehlermeldung.
- [ ] **M4 — Eigenschaften:** Node anklicken, Titel ändern → der neue Titel ist
      sofort auf dem Node sichtbar.
- [ ] **M5 — Speichern:** Namen ändern, „Speichern" → sichtbare Bestätigung
      („Gespeichert"), keine Fehlermeldung.
- [ ] **M6 — Neuladen:** Seite neu laden → Name, Nodes und Kanten sind
      unverändert vorhanden.
- [ ] **M7 — Validieren (gültig):** „Validieren" → Meldung „Workflow ist gültig".
- [ ] **M8 — Validieren (ungültig):** „Ende"-Node löschen, „Validieren" →
      verständliche Fehlermeldung, die das fehlende Ende benennt.
- [ ] **M9 — Ausführen:** „Ausführen", Eingabe „Hallo" bestätigen → Ergebnis
      erscheint, die Reihenfolge enthält Start und Ende.
- [ ] **M10 — Wiederfinden:** Zur Workflow-Liste, den Workflow öffnen → der
      Editor zeigt ihn, **ohne** dass die Liste darüber liegt.
- [ ] **M11 — Löschen:** In der Liste „Löschen" → Rückfrage, danach ist der
      Eintrag verschwunden.
- [ ] **M12 — Handy:** Fenster auf Handybreite → Palette bleibt bedienbar,
      Panel lässt sich öffnen und schließen.

Diese zwölf Punkte sind die Abnahme. Die Automatisierung bildet sie 1:1 ab.

---

## 5. Nutzer-Journeys (automatisiert)

Jede Journey nennt Ziel, Vorgehen aus Nutzersicht, sichtbares Ergebnis und die
Prüfpunkte. Technische Hinweise stehen getrennt darunter.

### J1 — Ersten Workflow von null bauen

**Ziel:** Ein Nutzer erstellt ohne Vorkenntnisse einen lauffähigen Workflow.
**Vorgehen:** Editor öffnen → „Neu (Start+End)" → „Agent" hinzufügen →
Start mit Agent verbinden → Agent mit Ende verbinden → Namen vergeben →
„Speichern".
**Sichtbares Ergebnis:** Drei Nodes, zwei Kanten, Bestätigung „Gespeichert".
**Prüfpunkte:** Node-Anzahl, Kanten-Anzahl, sichtbarer Name, Bestätigung.
**Technik:** Keine Fixtures nötig — dieser Test legt alles über die UI an.
Langsamer, aber der einzige echte End-to-End-Nachweis.

### J2 — Workflow wiederfinden und öffnen

**Ziel:** Ein Nutzer findet einen gespeicherten Workflow wieder.
**Vorgehen:** Workflow-Liste öffnen → Eintrag wählen → „Laden".
**Sichtbares Ergebnis:** Editor zeigt den Workflow; die Liste ist geschlossen.
**Prüfpunkte:** Name im Editor, Node-/Kanten-Anzahl, Liste nicht mehr sichtbar.
**Technik:** Entspricht dem Fix „Liste nur zeigen, wenn kein Workflow gewählt
ist". Fixture per API anlegen ist hier sinnvoll (Setup, nicht Prüfung).

### J3 — Agent konfigurieren

**Ziel:** Ein Nutzer passt Modell und Prompt eines Agent-Nodes an.
**Vorgehen:** Agent-Node anklicken → Modell wählen → Prompt ändern → speichern
→ neu laden.
**Sichtbares Ergebnis:** Werte stehen nach dem Neuladen unverändert im Panel.
**Prüfpunkte:** Modell, Prompt, Persistenz nach Reload.

### J4 — Classify konfigurieren

**Ziel:** Ein Nutzer richtet eine Klassifikation ein.
**Vorgehen:** Classify-Node hinzufügen → Modellname und Fragen-JSON eintragen →
speichern → neu laden.
**Sichtbares Ergebnis:** Konfiguration bleibt erhalten; bei ungültigem JSON
erscheint eine verständliche Fehlermeldung statt eines Absturzes.
**Prüfpunkte:** Werte persistiert, Fehlerfall sichtbar und nicht blockierend.

### J5 — Fehler finden und beheben

**Ziel:** Ein Nutzer erkennt einen ungültigen Workflow und repariert ihn.
**Vorgehen:** Ende-Node löschen → „Validieren" → Fehlermeldung lesen → Ende
wieder hinzufügen und verbinden → erneut validieren.
**Sichtbares Ergebnis:** Erst konkrete Fehlermeldung, danach „Workflow ist
gültig".
**Prüfpunkte:** Fehlertext nennt das Problem; nach Korrektur verschwindet er.

### J6 — Workflow ausführen

**Ziel:** Ein Nutzer startet einen Workflow und liest das Ergebnis.
**Vorgehen:** „Ausführen" → Eingabe bestätigen → Ergebnis ansehen.
**Sichtbares Ergebnis:** Ergebnisbereich zeigt Antwort und Reihenfolge der
Nodes.
**Prüfpunkte:** Ergebnis vorhanden, Reihenfolge enthält Start und Ende, keine
Fehlermeldung.
**Technik:** Testprofil mit scripted Provider für feste Antwort.

### J7 — Auf dem Handy bedienen

**Ziel:** Ein Nutzer arbeitet mit kleinem Bildschirm.
**Vorgehen:** Handy-Viewport → Workflow öffnen → Node hinzufügen → Panel
öffnen/schließen.
**Sichtbares Ergebnis:** Palette scrollbar und bedienbar, Panel überlagert den
Editor nicht dauerhaft.
**Prüfpunkte:** Node wird angelegt, Panel schließt wieder, Fläche bleibt
bedienbar.

### J8 — Fehlerfall: Backend nicht erreichbar

**Ziel:** Ein Nutzer bekommt bei Ausfall eine verständliche Meldung.
**Vorgehen:** Netzwerk zu `/api/workflows/*` blockieren → „Speichern".
**Sichtbares Ergebnis:** Meldung „Speichern fehlgeschlagen", keine weiße Seite,
Eingaben bleiben erhalten.
**Prüfpunkte:** sichtbare Fehlermeldung, Editor weiter bedienbar.

---

## 6. Testdaten

Fixtures sind **Setup-Erleichterung**, nicht der Prüfgegenstand.

- **J1** baut alles über die UI — keine Fixtures.
- **J2–J8** dürfen einen Workflow per `PUT /api/workflows/{id}` anlegen, prüfen
  aber ausschließlich sichtbares Verhalten.
- IDs immer eindeutig: `e2e-<timestamp>-<random>`, Löschung im `afterEach`.
- Negativfall (J5) startet mit einem Workflow ohne Ende-Node.

Beispiel-Minimalworkflow:

```json
{
  "id": "e2e-minimal",
  "name": "e2e-minimal",
  "version": "1.0.0",
  "status": "draft",
  "agents": [],
  "nodes": [
    { "id": "start-1", "type": "start", "position": { "x": 0, "y": 0 }, "data": { "title": "Start" } },
    { "id": "end-1",   "type": "end",   "position": { "x": 300, "y": 0 }, "data": { "title": "Ende" } }
  ],
  "edges": [
    { "id": "e-start-end", "source": "start-1", "target": "end-1", "type": "labeled" }
  ]
}
```

---

## 7. Testumgebung

```bash
cd qoc

ENGINE_STORAGE=file \
ENGINE_WORKFLOW_STORAGE_DIR=/tmp/qoc-e2e/workflows \
ENGINE_WORKSPACE=/tmp/qoc-e2e/workspace \
ENGINE_PROVIDER_SCRIPTED=true \
mvn -pl quarkus quarkus:dev -Dquarkus.http.port=8091
```

- aktueller React-Build (nicht der Legacy-Build),
- SPA-Fallback aktiv, damit `/workflows/:id` direkt lädt,
- Auth im Test aus (`config.js`: `oidcEnabled:false`).

```bash
E2E_BASE_URL=http://localhost:8091
E2E_API_BASE=http://localhost:8091/api
```

---

## 8. Playwright-Skizze (nutzernahe Selektoren)

```ts
import { test, expect, request } from '@playwright/test';

const API = process.env.E2E_API_BASE ?? 'http://localhost:8091/api';

test('J1: Nutzer baut einen Workflow von null', async ({ page }) => {
  await page.goto('/editor');

  await page.getByRole('button', { name: 'Neu (Start+End)' }).click();

  await expect(page.getByText('Start', { exact: true })).toBeVisible();
  await expect(page.getByText('Ende', { exact: true })).toBeVisible();

  await page.getByRole('button', { name: 'Agent', exact: true }).click();
  await expect(page.getByRole('heading', { name: /agent-\d+/ })).toBeVisible();

  await page.getByLabel('Workflow-Name').fill('Mein erster Workflow');
  await page.getByRole('button', { name: 'Speichern' }).click();

  await expect(page.getByText(/Gespeichert/)).toBeVisible();
});

test('J5: Nutzer behebt einen Validierungsfehler', async ({ page }) => {
  await page.goto('/editor');
  await page.getByRole('button', { name: 'Neu (Start+End)' }).click();

  await page.getByTestId('node-end-1').click();
  await page.getByRole('button', { name: 'Löschen' }).click();

  await page.getByRole('button', { name: 'Validieren' }).click();
  await expect(page.getByText(/Ende.*fehlt|fehlendes Ende/i)).toBeVisible();

  await page.getByRole('button', { name: 'Ende', exact: true }).click();
  await page.getByRole('button', { name: 'Validieren' }).click();
  await expect(page.getByText(/gültig/i)).toBeVisible();
});
```

Verbindung per Handle-Drag bleibt technisch (Bounding-Boxen), ist aber die
einzige Stelle ohne semantische Alternative:

```ts
async function connect(page, sourceTestId: string, targetTestId: string) {
  const s = await page.getByTestId(sourceTestId).locator('.react-flow__handle.source').boundingBox();
  const t = await page.getByTestId(targetTestId).locator('.react-flow__handle.target').boundingBox();
  if (!s || !t) throw new Error('Handle nicht sichtbar');
  await page.mouse.move(s.x + s.width / 2, s.y + s.height / 2);
  await page.mouse.down();
  await page.mouse.move(t.x + t.width / 2, t.y + t.height / 2, { steps: 10 });
  await page.mouse.up();
}
```

---

## 9. Konfiguration

Die bestehende Root-`playwright.config.js` zeigt noch auf die Legacy-UI
(`:8071`). Empfehlung: getrennte Config.

```js
// playwright.workflow.config.js
const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests/workflow',
  timeout: 45_000,
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8091',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  reporter: [['list'], ['html', { open: 'never' }]],
});
```

```bash
npx playwright test --config=playwright.workflow.config.js
```

---

## 10. Abnahmekriterien

1. Die manuelle Checkliste (Abschnitt 4) ist vollständig in unter 10 Minuten
   durchführbar, ohne API-Kenntnisse.
2. Alle Journeys laufen headless grün, dreimal hintereinander ohne Flakes.
3. Keine Test-Workflows bleiben zurück.
4. Selektion folgt Abschnitt 3; `data-testid` nur an Canvas-Nodes/Handles.
5. Fehler erzeugen Screenshot und Trace.
6. Geprüft wird sichtbares Verhalten **und** die Persistenz nach Reload.

---

## 11. Risiken

| Risiko | Auswirkung | Gegenmaßnahme |
|---|---|---|
| `alert()`/`confirm()` | blockiert Ablauf, nicht nutzerfreundlich | Toast/Inline (Abschnitt 2, Punkt 1) |
| Icon-Buttons ohne Namen | weder Screenreader noch Test finden sie | `aria-label` ergänzen |
| ReactFlow-Drag | Timing-anfällig | Handle-Bounding-Boxen, `steps`, ggf. Retry |
| Legacy-Server auf `:8081` | Test prüft falsche UI | eigener Port `:8091` mit aktuellem Build |
| Geteilte Datenbank | fremde Workflows in der Liste | File-Store oder eindeutige IDs + Cleanup |
| LLM im Run | langsam/instabil | `ENGINE_PROVIDER_SCRIPTED=true` |
| SPA-Deep-Link 404 | Navigation schlägt fehl | SPA-Fallback prüfen |

---

## 12. Offene Entscheidungen

1. Werden `alert()`/`confirm()` ersetzt (empfohlen, Voraussetzung für J5/J8)?
2. Werden Rollen/Namen und `aria-label` ergänzt, oder übergangsweise
   `data-testid` an allen Bedienelementen?
3. Bleibt `tests/workflow-ui.spec.js` (Legacy-UI) bestehen oder wird es ersetzt?

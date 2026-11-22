# UI Workflow Editor Tests — User-Centered Test Plan

**Status:** Test plan (2026-11-22) — Not an implementation plan; describes UI workflow editor test scenarios.
**Reference:** `reactflow/src/EditorPage.tsx`, `reactflow/src/pages/Workflows.tsx`, Workflow API (`/api/workflows`), Playwright
**Not part of this plan:** Auth/RBAC (see `ui-security-tests.md`), Chat UI, Runs/Observe pages.

---

## 1. Testing attitude: like a user, not like the DOM

The test describes **what a user sees and does**, not how the UI is built
internally. Three rules follow from this:

1. **Black box.** Only what is visible is checked: nodes on the
   canvas, labels, dialogs, status messages, result lists.
2. **Users find elements by name and role**, not by CSS classes.
   A button is called "Speichern", a field "Workflow-Name".
3. **Every journey is also manually reproducible.** Anyone working through the
   checklist in section 4 tests the same flow without Playwright knowledge.

Automation is the repetition of manual verification — not a replacement for it.

---

## 2. What the app lacks so users (and tests) can verify it

These points are the real preparatory work. Without them every test remains brittle.

| # | Measure | Why it helps | Effort |
|---|---|---|---|
| 1 | Replace `alert()`/`confirm()` with toast or inline status | A user sees "Gespeichert"/"Fehler" persistently; the test can read the same message instead of catching a blocking browser dialog | medium |
| 2 | Provide controls with roles and names (`<button>`, `aria-label`, `<label for>`) | `getByRole`/`getByLabel` then works for screen readers **and** Playwright; icon buttons become addressable at all | small |
| 3 | Show validation errors inline (list in panel, marker on node) | User recognizes what is wrong; test checks text instead of `alert` content | medium |
| 4 | Keep save status visible ("Gespeichert 12:34", "Nicht gespeichert") | User knows whether they can navigate safely; test has a stable signal for persistence | small |
| 5 | Error Boundary around the editor | Backend outage produces a message instead of a white page | small |
| 6 | Entry points "Neu (Start+End)" and "Beispiel-Workflow" | A user can start without API/Postman; the test need not require fixtures | small |
| 7 | Test profile with deterministic provider (`ENGINE_PROVIDER_SCRIPTED=true`) | "Ausführen" returns a fixed answer; run tests become reproducible | small |
| 8 | Animations respect `prefers-reduced-motion`, auto-layout deterministic | Fewer timing flakes when dragging and connecting | small |
| 9 | `data-testid` **only** for canvas nodes and handles | There is no semantic role there; everywhere else roles/names remain the first choice | small |

Points 1 and 2 are the most important: they make the UI both more usable and
more testable — same effort, double benefit.

---

## 3. Selection rules (priority top to bottom)

| Priority | Selector | Example |
|---|---|---|
| 1 | Role + name | `getByRole('button', { name: 'Speichern' })` |
| 2 | Label | `getByLabel('Workflow-Name')` |
| 3 | Visible text | `getByText('Workflow ist gültig')` |
| 4 | `data-testid` | `getByTestId('node-start-1')` |
| 5 | CSS (counting/structure only) | `.react-flow__edge` |

`npx playwright codegen <url>` produces exactly these user-facing selectors and is
the practical tool for checking whether the UI is "discoverable".

---

## 4. Manual test run (checklist, approx. 10 minutes)

Prerequisite: App running, a workflow selected or "Neu" clicked.
Each item counts as passed when the **visible result** occurs.

- [ ] **M1 — Empty start:** "Neu (Start+End)" → canvas shows exactly two nodes,
      "Start" and "Ende", connected by an edge.
- [ ] **M2 — Add node:** Choose "Agent" in the palette → a node appears, the
      properties panel opens and shows the node ID.
- [ ] **M3 — Connect:** Drag from the output of "Start" to the input of the new
      node → a second edge appears, without an error message.
- [ ] **M4 — Properties:** Click the node, change the title → the new title is
      immediately visible on the node.
- [ ] **M5 — Save:** Change the name, "Speichern" → visible confirmation
      ("Gespeichert"), no error message.
- [ ] **M6 — Reload:** Reload the page → name, nodes and edges are still present
      unchanged.
- [ ] **M7 — Validate (valid):** "Validieren" → message "Workflow ist gültig".
- [ ] **M8 — Validate (invalid):** Delete the "Ende" node, "Validieren" →
      understandable error message naming the missing end.
- [ ] **M9 — Run:** "Ausführen", confirm input "Hallo" → result appears, the
      order contains Start and Ende.
- [ ] **M10 — Find again:** Go to the workflow list, open the workflow → the
      editor shows it, **without** the list overlaying it.
- [ ] **M11 — Delete:** "Löschen" in the list → confirmation prompt, afterwards
      the entry is gone.
- [ ] **M12 — Mobile:** Window at mobile width → palette remains usable, panel
      can be opened and closed.

These twelve points are the acceptance. Automation maps them 1:1.

---

## 5. User journeys (automated)

Each journey names goal, steps from the user's perspective, visible result and
checkpoints. Technical notes are listed separately below.

### J1 — Build a first workflow from scratch

**Goal:** A user creates a working workflow without prior knowledge.
**Steps:** Open editor → "Neu (Start+End)" → add "Agent" → connect Start to Agent
→ connect Agent to Ende → assign a name → "Speichern".
**Visible result:** Three nodes, two edges, confirmation "Gespeichert".
**Checkpoints:** Node count, edge count, visible name, confirmation.
**Technique:** No fixtures needed — this test creates everything through the UI.
Slower, but the only true end-to-end proof.

### J2 — Find and open a workflow

**Goal:** A user finds a saved workflow again.
**Steps:** Open workflow list → select entry → "Laden".
**Visible result:** Editor shows the workflow; the list is closed.
**Checkpoints:** Name in editor, node/edge count, list no longer visible.
**Technique:** Corresponds to the fix "show list only when no workflow is
selected". Creating a fixture via API is sensible here (setup, not verification).

### J3 — Configure an agent

**Goal:** A user adjusts model and prompt of an agent node.
**Steps:** Click agent node → choose model → change prompt → save → reload.
**Visible result:** Values are unchanged in the panel after reload.
**Checkpoints:** Model, prompt, persistence after reload.

### J4 — Configure classify

**Goal:** A user sets up a classification.
**Steps:** Add classify node → enter model name and questions JSON → save →
reload.
**Visible result:** Configuration is retained; on invalid JSON an understandable
error message appears instead of a crash.
**Checkpoints:** Values persisted, error case visible and non-blocking.

### J5 — Find and fix errors

**Goal:** A user recognizes an invalid workflow and repairs it.
**Steps:** Delete end node → "Validieren" → read error message → add end node
again and connect → validate again.
**Visible result:** First a concrete error message, then "Workflow ist gültig".
**Checkpoints:** Error text names the problem; it disappears after correction.

### J6 — Run a workflow

**Goal:** A user starts a workflow and reads the result.
**Steps:** "Ausführen" → confirm input → view result.
**Visible result:** Result area shows answer and order of nodes.
**Checkpoints:** Result present, order contains Start and Ende, no error message.
**Technique:** Test profile with scripted provider for a fixed answer.

### J7 — Operate on mobile

**Goal:** A user works with a small screen.
**Steps:** Mobile viewport → open workflow → add node → open/close panel.
**Visible result:** Palette scrollable and usable, panel does not permanently
overlay the editor.
**Checkpoints:** Node is created, panel closes again, canvas remains usable.

### J8 — Failure case: backend unreachable

**Goal:** A user gets an understandable message on outage.
**Steps:** Block network to `/api/workflows/*` → "Speichern".
**Visible result:** Message "Speichern fehlgeschlagen", no white page, inputs
remain intact.
**Checkpoints:** Visible error message, editor still usable.

---

## 6. Test data

Fixtures are **setup aids**, not the subject under test.

- **J1** builds everything through the UI — no fixtures.
- **J2–J8** may create a workflow via `PUT /api/workflows/{id}`, but verify
  exclusively visible behavior.
- IDs always unique: `e2e-<timestamp>-<random>`, deletion in `afterEach`.
- Negative case (J5) starts with a workflow without an end node.

Example minimal workflow:

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

## 7. Test environment

```bash
cd qoc

ENGINE_STORAGE=file \
ENGINE_WORKFLOW_STORAGE_DIR=/tmp/qoc-e2e/workflows \
ENGINE_WORKSPACE=/tmp/qoc-e2e/workspace \
ENGINE_PROVIDER_SCRIPTED=true \
mvn -pl quarkus quarkus:dev -Dquarkus.http.port=8091
```

- current React build (not the legacy build),
- SPA fallback active so `/workflows/:id` loads directly,
- Auth off in tests (`config.js`: `oidcEnabled:false`).

```bash
E2E_BASE_URL=http://localhost:8091
E2E_API_BASE=http://localhost:8091/api
```

---

## 8. Playwright sketch (user-facing selectors)

```ts
import { test, expect, request } from '@playwright/test';

const API = process.env.E2E_API_BASE ?? 'http://localhost:8091/api';

test('J1: User builds a workflow from scratch', async ({ page }) => {
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

test('J5: User fixes a validation error', async ({ page }) => {
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

Connecting via handle drag remains technical (bounding boxes), but it is the only
place without a semantic alternative:

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

## 9. Configuration

The existing root `playwright.config.js` still points at the legacy UI
(`:8071`). Recommendation: separate config.

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

## 10. Acceptance criteria

1. The manual checklist (section 4) can be completed in under 10 minutes
   without API knowledge.
2. All journeys run headless green, three times in a row without flakes.
3. No test workflows remain.
4. Selection follows section 3; `data-testid` only on canvas nodes/handles.
5. Errors produce screenshot and trace.
6. Visible behavior **and** persistence after reload are verified.

---

## 11. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| `alert()`/`confirm()` | blocks flow, not user-friendly | Toast/inline (section 2, point 1) |
| Icon buttons without names | neither screen reader nor test finds them | add `aria-label` |
| ReactFlow drag | timing-sensitive | handle bounding boxes, `steps`, retry if needed |
| Legacy server on `:8081` | test checks wrong UI | own port `:8091` with current build |
| Shared database | foreign workflows in the list | file store or unique IDs + cleanup |
| LLM in run | slow/unstable | `ENGINE_PROVIDER_SCRIPTED=true` |
| SPA deep link 404 | navigation fails | check SPA fallback |

---

## 12. Open decisions

1. Will `alert()`/`confirm()` be replaced (recommended, prerequisite for J5/J8)?
2. Will roles/names and `aria-label` be added, or will `data-testid` be used
   on all controls as an interim solution?
3. Does `tests/workflow-ui.spec.js` (legacy UI) remain or get replaced?

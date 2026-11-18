# UI-Sicherheitstests — Testplan

**Status:** Plan (2026-11-18)
**Bezug:** OIDC, RBAC, Session-Ownership, Trusted Proxy, Egress, MCP-OAuth, Workflow/Agent-Ownership

---

## Voraussetzungen (Prerequisites)

- Quarkus-Backend läuft (`mvn quarkus:dev` oder `./start.sh`), DB (Postgres/SQLite) initialisiert.
- OIDC-Provider (Keycloak/Auth0) konfiguriert **oder** BASIC-Modus mit Test-Usern.
- Zwei Test-Accounts: `admin` (Role `admin`) und `user1` (Role `user`, kein admin).
- Mindestens 2 Sessions, 2 Workflows, 2 Agents mit unterschiedlichen Ownern.
- Browser mit DevTools (Network/Console) für Token-Inspektion.
- Test-Runner: Playwright (bereits im Repo: `playwright.config.js`) oder manuell.

### Umgebungsvariablen

```bash
ENGINE_SECURITY_MODE=OIDC   # oder BASIC / PROXY / NONE (nur Dev)
ENGINE_WORKSPACE=/tmp/qoc-test-workspace
# OIDC (nur bei OIDC-Modus):
QUARKUS_OIDC_AUTH_SERVER_URL=https://keycloak/realms/qoc
QUARKUS_OIDC_CLIENT_ID=qoc-ui
```

---

## Test-Suites

### A. Authentifizierung (OIDC/BASIC/PROXY)

| ID | Test | Schritte | Erwartung |
|---|---|---|---|
| A1 | Login-Flow (OIDC) | 1. `/core/` öffnen 2. Login-Button klicken 3. OIDC-Redirect 4. Callback | Token in `sessionStorage`, UI zeigt User |
| A2 | Login-Flow (BASIC) | 1. `ENGINE_SECURITY_MODE=BASIC` 2. Basic-Auth-Dialog 3. Credentials | Zugriff auf `/api/*` |
| A3 | Unauthentifizierter Zugriff | Ohne Token `/api/session` aufrufen | 401 + WWW-Authenticate |
| A4 | Token-Expiry | Abgelaufenen Token verwenden | 401, Refresh-Flow oder Logout |
| A5 | Logout | Logout-Button klicken | Token entfernt, Redirect zu Login |

### B. Session-Ownership (RBAC)

| ID | Test | Schritte | Erwartung |
|---|---|---|---|
| B1 | Eigene Session sichtbar | user1 erstellt Session, listet Sessions | Session in Liste |
| B2 | Fremde Session unsichtbar | user1 versucht Session von user2 zu lesen | 403 "not session owner" |
| B3 | Admin sieht alle | admin listet Sessions | Alle Sessions sichtbar |
| B4 | Delete fremde Session | user1 löscht Session von user2 | 403 |
| B5 | Delete eigene Session | user1 löscht eigene Session | 204, aus Liste entfernt |

### C. Workflow-Ownership

| ID | Test | Schritte | Erwartung |
|---|---|---|---|
| C1 | Workflow erstellen + Owner | user1 erstellt Workflow via UI | `ownerId = user1` |
| C2 | Fremder Workflow-Zugriff | user2 GET `/workflows/{id}` (owner user1) | 403 |
| C3 | Shared Workflow (leerer Owner) | Workflow mit `ownerId=""` | Beide User können lesen |
| C4 | Generate erbt Owner | user1 generiert Workflow | `ownerId = user1` |

### D. Agent-Ownership & Authoring

| ID | Test | Schritte | Erwartung |
|---|---|---|---|
| D1 | Admin erstellt Agent | admin POST `/agent` | 200 ok |
| D2 | Non-Admin erstellt Agent | user1 POST `/agent` | 403 |
| D3 | Agent-Store-Ownership | user1 erstellt Agent via `/agents` | nur eigene sichtbar (wenn ownerId gesetzt) |

### E. Trusted Proxy / Header-Spoofing

| ID | Test | Schritte | Erwartung |
|---|---|---|---|
| E1 | Trusted Proxy erlaubt | Request von trusted IP mit `X-Forwarded-User` | Principal gesetzt |
| E2 | Untrusted Proxy blockiert | Request von nicht-trusted IP mit Spoofed-Header | Header ignoriert, 401/403 |

### F. Egress-Schutz

| ID | Test | Schritte | Erwartung |
|---|---|---|---|
| F1 | A2A-Tool zu erlaubtem Host | A2A-Call zu `allowedHosts`-Eintrag | Erfolg |
| F2 | A2A-Tool zu blockiertem Host | A2A-Call zu nicht erlaubtem Host | `egress_blocked` Fehler |
| F3 | Webhook zu blockiertem Host | Webhook-URL nicht in Allowlist | `egress_blocked` Log |

### G. MCP-OAuth

| ID | Test | Schritte | Erwartung |
|---|---|---|---|
| G1 | OAuth-Flow starten | MCP-Server mit OAuth konfigurieren | Redirect zu OAuth-Provider |
| G2 | Token-Refresh | Abgelaufenes Token nutzen | Automatischer Refresh |

### H. UI-Sichtbarkeit

| ID | Test | Schritte | Erwartung |
|---|---|---|---|
| H1 | Admin-UI-Elemente | Als admin einloggen | "New agent", "Reload Config", Delete-Buttons sichtbar |
| H2 | Non-Admin-UI-Elemente | Als user1 einloggen | Authoring-Buttons ausgeblendet/deaktiviert |
| H3 | Session-Dropdown zeigt nur eigene | user1 öffnet Chat | Nur eigene Sessions im Dropdown |

---

## Playwright-Skizzen

### A1: OIDC-Login setzt Token

```typescript
test('A1: OIDC login sets token', async ({ page }) => {
  await page.goto('/core/');
  await page.click('#loginBtn');
  const token = await page.evaluate(() => sessionStorage.getItem('qoc.accessToken'));
  expect(token).toBeTruthy();
});
```

### B2: Fremde Session liefert 403

```typescript
test('B2: foreign session returns 403', async ({ request }) => {
  const res = await request.get('/api/session/' + foreignSessionId, {
    headers: { Authorization: 'Bearer ' + user1Token },
  });
  expect(res.status()).toBe(403);
});
```

### C2: Fremder Workflow-Zugriff

```typescript
test('C2: foreign workflow returns 403', async ({ request }) => {
  const res = await request.get('/api/workflows/' + foreignWorkflowId, {
    headers: { Authorization: 'Bearer ' + user2Token },
  });
  expect(res.status()).toBe(403);
});
```

### D2: Non-Admin kann keinen Agent anlegen

```typescript
test('D2: non-admin agent create returns 403', async ({ request }) => {
  const res = await request.post('/api/agent', {
    headers: { Authorization: 'Bearer ' + user1Token, 'Content-Type': 'application/json' },
    data: { name: 'test-agent', content: '---\nname: test-agent\n---\n' },
  });
  expect(res.status()).toBe(403);
});
```

### H1/H2: UI-Elemente rollenbasiert

```typescript
test('H1: admin sees authoring buttons', async ({ page }) => {
  await loginAs(page, 'admin');
  await page.goto('/agents');
  await expect(page.getByRole('button', { name: '+ New agent' })).toBeVisible();
});

test('H2: non-admin hides authoring buttons', async ({ page }) => {
  await loginAs(page, 'user1');
  await page.goto('/agents');
  await expect(page.getByRole('button', { name: '+ New agent' })).not.toBeVisible();
});
```

---

## Playwright-Konfiguration (Hinweis)

`playwright.config.js` im Repo-Root. Für Security-Tests eine separate Config mit `baseURL` und Auth-Setup:

```js
// playwright.security.config.js
module.exports = {
  ...require('./playwright.config.js'),
  testDir: './tests/security',
  use: { baseURL: 'http://localhost:8080' },
};
```

Ausführung:
```bash
npx playwright test --config=playwright.security.config.js
```

---

## Hinweise

- Für CI: `ENGINE_SECURITY_MODE=BASIC` mit Test-Usern (einfacher als OIDC-Testcontainer).
- OIDC-Tests (A1, A4, A5) erfordern einen laufenden Keycloak/Auth0-Testrealm.
- Egress-Tests (F1–F3) erfordern konfigurierte Allowlists (`engine.a2a.egress.allowedHosts`, `engine.webhook.url`).
- MCP-OAuth-Tests (G1, G2) erfordern einen OAuth-fähigen MCP-Server.

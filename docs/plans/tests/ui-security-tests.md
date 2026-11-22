# UI Security Tests — Test Plan

**Status:** Test plan (2026-11-22) — Not an implementation plan; describes UI security test scenarios.
**Reference:** OIDC, RBAC, Session-Ownership, Trusted Proxy, Egress, MCP-OAuth, Workflow/Agent-Ownership

---

## Prerequisites

- Quarkus backend running (`mvn quarkus:dev` or `./start.sh`), DB (Postgres/SQLite) initialized.
- OIDC provider (Keycloak/Auth0) configured **or** BASIC mode with test users.
- Two test accounts: `admin` (Role `admin`) and `user1` (Role `user`, no admin).
- At least 2 sessions, 2 workflows, 2 agents with different owners.
- Browser with DevTools (Network/Console) for token inspection.
- Test runner: Playwright (already in repo: `playwright.config.js`) or manual.

### Environment Variables

```bash
ENGINE_SECURITY_MODE=OIDC   # or BASIC / PROXY / NONE (dev only)
ENGINE_WORKSPACE=/tmp/qoc-test-workspace
# OIDC (only in OIDC mode):
QUARKUS_OIDC_AUTH_SERVER_URL=https://keycloak/realms/qoc
QUARKUS_OIDC_CLIENT_ID=qoc-ui
```

---

## Test Suites

### A. Authentication (OIDC/BASIC/PROXY)

| ID | Test | Steps | Expected |
|---|---|---|---|
| A1 | Login flow (OIDC) | 1. Open `/core/` 2. Click login button 3. OIDC redirect 4. Callback | Token in `sessionStorage`, UI shows user |
| A2 | Login flow (BASIC) | 1. `ENGINE_SECURITY_MODE=BASIC` 2. Basic-Auth dialog 3. Credentials | Access to `/api/*` |
| A3 | Unauthenticated access | Call `/api/session` without token | 401 + WWW-Authenticate |
| A4 | Token expiry | Use expired token | 401, refresh flow or logout |
| A5 | Logout | Click logout button | Token removed, redirect to login |

### B. Session Ownership (RBAC)

| ID | Test | Steps | Expected |
|---|---|---|---|
| B1 | Own session visible | user1 creates session, lists sessions | Session in list |
| B2 | Foreign session invisible | user1 tries to read user2's session | 403 "not session owner" |
| B3 | Admin sees all | admin lists sessions | All sessions visible |
| B4 | Delete foreign session | user1 deletes user2's session | 403 |
| B5 | Delete own session | user1 deletes own session | 204, removed from list |

### C. Workflow Ownership

| ID | Test | Steps | Expected |
|---|---|---|---|
| C1 | Create workflow + owner | user1 creates workflow via UI | `ownerId = user1` |
| C2 | Foreign workflow access | user2 GET `/workflows/{id}` (owner user1) | 403 |
| C3 | Shared workflow (empty owner) | Workflow with `ownerId=""` | Both users can read |
| C4 | Generate inherits owner | user1 generates workflow | `ownerId = user1` |

### D. Agent Ownership & Authoring

| ID | Test | Steps | Expected |
|---|---|---|---|
| D1 | Admin creates agent | admin POST `/agent` | 200 ok |
| D2 | Non-admin creates agent | user1 POST `/agent` | 403 |
| D3 | Agent store ownership | user1 creates agent via `/agents` | only own visible (when ownerId set) |

### E. Trusted Proxy / Header Spoofing

| ID | Test | Steps | Expected |
|---|---|---|---|
| E1 | Trusted proxy allowed | Request from trusted IP with `X-Forwarded-User` | Principal set |
| E2 | Untrusted proxy blocked | Request from non-trusted IP with spoofed header | Header ignored, 401/403 |

### F. Egress Protection

| ID | Test | Steps | Expected |
|---|---|---|---|
| F1 | A2A tool to allowed host | A2A call to an `allowedHosts` entry | Success |
| F2 | A2A tool to blocked host | A2A call to a non-allowed host | `egress_blocked` error |
| F3 | Webhook to blocked host | Webhook URL not in allowlist | `egress_blocked` log |

### G. MCP-OAuth

| ID | Test | Steps | Expected |
|---|---|---|---|
| G1 | Start OAuth flow | Configure MCP server with OAuth | Redirect to OAuth provider |
| G2 | Token refresh | Use expired token | Automatic refresh |

### H. UI Visibility

| ID | Test | Steps | Expected |
|---|---|---|---|
| H1 | Admin UI elements | Log in as admin | "New agent", "Reload Config", Delete buttons visible |
| H2 | Non-admin UI elements | Log in as user1 | Authoring buttons hidden/disabled |
| H3 | Session dropdown shows only own | user1 opens chat | Only own sessions in dropdown |

---

## Playwright Sketches

### A1: OIDC login sets token

```typescript
test('A1: OIDC login sets token', async ({ page }) => {
  await page.goto('/core/');
  await page.click('#loginBtn');
  const token = await page.evaluate(() => sessionStorage.getItem('qoc.accessToken'));
  expect(token).toBeTruthy();
});
```

### B2: Foreign session returns 403

```typescript
test('B2: foreign session returns 403', async ({ request }) => {
  const res = await request.get('/api/session/' + foreignSessionId, {
    headers: { Authorization: 'Bearer ' + user1Token },
  });
  expect(res.status()).toBe(403);
});
```

### C2: Foreign workflow access

```typescript
test('C2: foreign workflow returns 403', async ({ request }) => {
  const res = await request.get('/api/workflows/' + foreignWorkflowId, {
    headers: { Authorization: 'Bearer ' + user2Token },
  });
  expect(res.status()).toBe(403);
});
```

### D2: Non-admin cannot create agent

```typescript
test('D2: non-admin agent create returns 403', async ({ request }) => {
  const res = await request.post('/api/agent', {
    headers: { Authorization: 'Bearer ' + user1Token, 'Content-Type': 'application/json' },
    data: { name: 'test-agent', content: '---\nname: test-agent\n---\n' },
  });
  expect(res.status()).toBe(403);
});
```

### H1/H2: Role-based UI elements

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

## Playwright Configuration (Note)

`playwright.config.js` in repo root. For security tests, a separate config with `baseURL` and auth setup:

```js
// playwright.security.config.js
module.exports = {
  ...require('./playwright.config.js'),
  testDir: './tests/security',
  use: { baseURL: 'http://localhost:8080' },
};
```

Execution:
```bash
npx playwright test --config=playwright.security.config.js
```

---

## Notes

- For CI: `ENGINE_SECURITY_MODE=BASIC` with test users (simpler than OIDC test containers).
- OIDC tests (A1, A4, A5) require a running Keycloak/Auth0 test realm.
- Egress tests (F1–F3) require configured allowlists (`engine.a2a.egress.allowedHosts`, `engine.webhook.url`).
- MCP-OAuth tests (G1, G2) require an OAuth-capable MCP server.

const { test, expect, request } = require('/home/torsten/.nvm/versions/node/v22.16.0/lib/node_modules/@playwright/test');

const API = process.env.E2E_API_BASE || 'http://localhost:8091/api';

async function putWorkflow(id, def) {
  const ctx = await request.newContext();
  const res = await ctx.put(API + '/workflows/' + id, { data: def });
  expect(res.ok()).toBeTruthy();
  await ctx.dispose();
}

async function deleteWorkflow(id) {
  const ctx = await request.newContext();
  await ctx.delete(API + '/workflows/' + id);
  await ctx.dispose();
}

function minimalDef(id) {
  return {
    id,
    name: id,
    version: '1.0.0',
    status: 'draft',
    agents: [],
    nodes: [
      { id: 'start-1', type: 'start', position: { x: 0, y: 0 }, data: { title: 'Start' } },
      { id: 'end-1', type: 'end', position: { x: 300, y: 0 }, data: { title: 'Ende' } },
    ],
    edges: [{ id: 'e-start-end', source: 'start-1', target: 'end-1', type: 'labeled' }],
  };
}

function invalidDef(id) {
  return {
    id,
    name: id,
    version: '1.0.0',
    status: 'draft',
    agents: [],
    nodes: [{ id: 'start-1', type: 'start', position: { x: 0, y: 0 }, data: { title: 'Start' } }],
    edges: [],
  };
}

test.describe('workflow editor (user journeys)', () => {
  let id = '';

  test.beforeEach(async () => {
    id = 'e2e-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8);
    await putWorkflow(id, minimalDef(id));
  });

  test.afterEach(async () => {
    await deleteWorkflow(id).catch(() => {});
  });

  test('J2: deep link loads workflow without list overlay', async ({ page }) => {
    await page.goto('/workflows/' + id);
    await expect(page.locator('.react-flow__node')).toHaveCount(2);
    await expect(page.locator('.react-flow__edge')).toHaveCount(1);
    await expect(page.getByTestId('workflow-name')).toHaveValue(id);
    await expect(page.getByTestId('list-overlay')).toHaveCount(0);
  });

  test('J1: user builds a workflow from scratch', async ({ page }) => {
    await page.goto('/editor');
    const listOverlay = page.getByTestId('list-overlay');
    if (await listOverlay.count()) {
      await page.getByRole('button', { name: 'Close' }).click();
    }

    await page.getByRole('button', { name: 'New (Start+End)' }).click();
    await expect(page.locator('.react-flow__node')).toHaveCount(2);
    await expect(page.locator('.react-flow__edge')).toHaveCount(1);

    await page.getByTestId('palette-agent').click();
    await expect(page.locator('.react-flow__node')).toHaveCount(3);
    await expect(page.getByTestId('property-panel')).toBeVisible();

    await page.getByTestId('workflow-name').fill('Mein erster Workflow');
    await page.getByTestId('toolbar-save').click();

    await page.reload();
    await expect(page.getByTestId('workflow-name')).toHaveValue('Mein erster Workflow');
    await expect(page.locator('.react-flow__node')).toHaveCount(3);
  });

  test('J5: user fixes a validation error', async ({ page }) => {
    await page.goto('/workflows/' + id);
    await page.locator('.react-flow__node[data-id="end-1"]').click();
    await page.keyboard.press('Delete');
    await expect(page.locator('.react-flow__node')).toHaveCount(1);

    await page.getByTestId('toolbar-validate').click();
    await expect(page.getByTestId('toast')).toContainText(/Missing end node/i);

    await page.getByTestId('palette-end').click();
    await page.getByTestId('toolbar-validate').click();
    await expect(page.getByTestId('toast')).toContainText(/valid/i);
  });

  test('J7: validation of a valid workflow shows success', async ({ page }) => {
    await page.goto('/workflows/' + id);
    await page.getByTestId('toolbar-validate').click();
    await expect(page.getByTestId('toast')).toContainText(/valid/i);
  });

  test('J6: user runs a minimal workflow', async ({ page }) => {
    await page.goto('/workflows/' + id);
    await page.getByTestId('toolbar-run').click();
    await expect(page.getByTestId('run-overlay')).toBeVisible();
    await page.getByTestId('run-overlay').getByRole('button', { name: 'Run' }).click();
    await expect(page.getByTestId('toast')).toContainText(/Ergebnis:/i);
  });

  test('J11: list overlay loads a workflow and closes', async ({ page }) => {
    await page.goto('/editor');
    const listOverlay = page.getByTestId('list-overlay');
    if (!(await listOverlay.count())) {
      await page.getByTestId('toolbar-list').click();
    }
    await expect(listOverlay).toBeVisible();
    const row = listOverlay.locator('div', { hasText: id });
    await row.getByRole('button', { name: 'Load' }).click();
    await expect(listOverlay).toHaveCount(0);
    await expect(page.getByTestId('workflow-name')).toHaveValue(id);
  });

  test('J12: delete from list removes the workflow', async ({ page }) => {
    await page.goto('/editor');
    const listOverlay = page.getByTestId('list-overlay');
    if (!(await listOverlay.count())) {
      await page.getByTestId('toolbar-list').click();
    }
    await expect(listOverlay).toBeVisible();
    const row = listOverlay.locator('div', { hasText: id });
    await row.getByRole('button', { name: 'Delete' }).click();
    await page.getByTestId('confirm-yes').click();
    await expect(listOverlay.locator('div', { hasText: id })).toHaveCount(0);

    const ctx = await request.newContext();
    const res = await ctx.get(API + '/workflows/' + id);
    expect(res.status()).toBe(404);
    await ctx.dispose();
  });

  test('J13: classify node can be added and configured', async ({ page }) => {
    await page.goto('/workflows/' + id);
    await page.getByTestId('palette-classify').click();
    await expect(page.locator('.react-flow__node')).toHaveCount(3);
    await expect(page.getByTestId('property-panel')).toBeVisible();
    await expect(page.getByTestId('property-panel').getByText(/Model Name/i)).toBeVisible();
  });

  test('J14: undo and redo', async ({ page }) => {
    await page.goto('/workflows/' + id);
    const before = await page.locator('.react-flow__node').count();
    await page.getByTestId('palette-agent').click();
    await expect(page.locator('.react-flow__node')).toHaveCount(before + 1);
    await page.getByTestId('toolbar-undo').click();
    await expect(page.locator('.react-flow__node')).toHaveCount(before);
    await page.getByTestId('toolbar-redo').click();
    await expect(page.locator('.react-flow__node')).toHaveCount(before + 1);
  });

  test('J17: mobile palette is usable', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/workflows/' + id);
    await page.getByTestId('palette-agent').click();
    await expect(page.locator('.react-flow__node')).toHaveCount(3);
  });
});

test.describe('workflow editor (negative)', () => {
  test('J8: save failure shows a toast, no white screen', async ({ page }) => {
    await page.route('**/api/workflows/**', (route) => route.abort());
    await page.goto('/editor');
    const listOverlay = page.getByTestId('list-overlay');
    if (await listOverlay.count()) {
      await page.getByRole('button', { name: 'Close' }).click();
    }
    await page.getByRole('button', { name: 'New (Start+End)' }).click();
    await page.getByTestId('toolbar-save').click();
    await expect(page.getByTestId('toast')).toBeVisible();
    await expect(page.getByTestId('workflow-canvas')).toBeVisible();
  });

  test('J5b: invalid workflow (no end) fails validation', async ({ page, request }) => {
    const badId = 'e2e-bad-' + Date.now();
    const ctx = await request.newContext();
    await ctx.put(API + '/workflows/' + badId, { data: invalidDef(badId) });
    await ctx.dispose();

    await page.goto('/workflows/' + badId);
    await page.getByTestId('toolbar-validate').click();
    await expect(page.getByTestId('toast')).toContainText(/Missing end node/i);

    const ctx2 = await request.newContext();
    await ctx2.delete(API + '/workflows/' + badId);
    await ctx2.dispose();
  });
});

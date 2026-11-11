const { test, expect } = require('/home/torsten/.nvm/versions/node/v22.16.0/lib/node_modules/@playwright/test');

test('agent chat UI loads', async ({ page }) => {
    await page.goto('http://localhost:8071/');
    await expect(page.locator('h1')).toContainText('QOC Engine');
});

test('workflow editor link present', async ({ page }) => {
    await page.goto('http://localhost:8071/');
    await expect(page.locator('a[href="/workflow/"]')).toBeVisible();
});

test('workflow UI loads', async ({ page }) => {
    await page.goto('http://localhost:8071/workflow/');
    const html = await page.content();
    expect(html.length).toBeGreaterThan(100);
});

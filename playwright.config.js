const { defineConfig } = require('/home/torsten/.nvm/versions/node/v22.16.0/lib/node_modules/@playwright/test');

module.exports = defineConfig({
    testDir: './tests',
    timeout: 45000,
    retries: 0,
    use: {
        baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8091',
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure',
    },
    reporter: [['list'], ['html', { open: 'never' }]],
});
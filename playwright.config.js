const { defineConfig } = require('/home/torsten/.nvm/versions/node/v22.16.0/lib/node_modules/@playwright/test');
module.exports = defineConfig({
    testDir: './tests',
    use: { baseURL: 'http://localhost:8071' },
    timeout: 30000,
});

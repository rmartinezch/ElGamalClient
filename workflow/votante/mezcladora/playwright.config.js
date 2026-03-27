const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './tests',
  timeout: 40000,
  expect: {
    timeout: 10000,
  },
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:4170',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  webServer: {
    command: 'VERIFICATUM_GUI_TESTMODE=1 VERIFICATUM_GUI_CLEAN_START=1 VERIFICATUM_GUI_RUNTIME_DIR=./runtime-e2e python3 server.py --host 127.0.0.1 --base-port 4170 --max-parties 5',
    cwd: __dirname,
    port: 4170,
    reuseExistingServer: false,
    timeout: 30000,
  },
});

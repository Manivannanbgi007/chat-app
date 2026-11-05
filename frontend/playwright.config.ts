import { defineConfig } from '@playwright/test'

// End-to-end tests run against a RUNNING stack (see README: "End-to-end tests").
// They drive the installed Google Chrome, so no browser download is needed.
export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    // The Docker frontend (nginx) by default. For the Vite dev server use E2E_BASE_URL=http://localhost:5210
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5280',
    channel: 'chrome',
    headless: true,
    // Only for a rehearsal of the production setup on your own computer (Caddy's own certificate authority, see
    // DEPLOYMENT_GUIDE.md "Try it on your own computer"): E2E_IGNORE_HTTPS_ERRORS=true
    ignoreHTTPSErrors: process.env.E2E_IGNORE_HTTPS_ERRORS === 'true',
    // Voice notes and calls: Chrome gets a fake microphone and camera (no hardware needed) and the permission is
    // granted without a prompt.
    permissions: ['microphone', 'camera'],
    launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
})

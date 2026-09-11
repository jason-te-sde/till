import { defineConfig, devices } from '@playwright/test'

/**
 * End-to-end, against the built bundle and a real service.
 *
 * `vite preview` rather than the dev server: what is being tested is what ships, and a bundler's
 * development mode differs from its output in exactly the ways that break in production.
 *
 * The API is expected to be running already — a service, a database and a schema are more than a
 * test runner should be starting. `e2e/till.ts` probes it and skips locally when it is not there,
 * and fails when CI says it should have been.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 1 : 0,
  // One worker. Every test in here moves the same stock, and two of them at once would be testing
  // each other rather than the service.
  workers: 1,
  reporter: process.env['CI'] ? [['github'], ['html', { open: 'never' }]] : [['list']],
  timeout: 30_000,
  expect: { timeout: 10_000 },

  use: {
    baseURL: process.env['TILL_WEB'] ?? 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },

  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],

  webServer: process.env['TILL_WEB']
    ? undefined
    : {
        command: 'npm run build && npm run preview -- --port 4173 --strictPort',
        url: 'http://127.0.0.1:4173',
        reuseExistingServer: !process.env['CI'],
        timeout: 120_000,
      },
})

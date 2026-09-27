import { defineConfig, devices } from '@playwright/test'

/**
 * End to end, against the whole stack: the edge, the store, the ledger, Kafka, Postgres, Redis and
 * Keycloak, exactly as `docker compose up` runs them.
 *
 * Nothing is started here. A database, a broker and an identity provider are more than a test runner
 * should own; the stack is brought up first (`docker compose up -d --wait` at the repository root), and
 * `e2e/stack.ts` stops the run with that instruction if it is not there.
 *
 * `localhost` rather than 127.0.0.1: Keycloak issues tokens in the name of localhost, and the sign-in
 * round trip needs the store's cookie and the provider's redirect to agree on a host.
 */
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  fullyParallel: false,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 1 : 0,
  // One worker. The tests share one ledger, and two buying the same game at once would be testing
  // each other rather than the store.
  workers: 1,
  reporter: process.env['CI'] ? [['github'], ['html', { open: 'never' }]] : [['list']],
  timeout: 60_000,
  expect: { timeout: 15_000 },

  use: {
    baseURL: process.env['TILL_WEB'] ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },

  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
})

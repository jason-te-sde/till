import { test as base, expect, type Locator, type Page } from '@playwright/test'

const API = process.env['TILL_API'] ?? 'http://127.0.0.1:8080'
const TOKEN = process.env['TILL_TOKEN'] ?? 'demo-admin-token'

/**
 * Whether the service is up.
 *
 * Probed rather than assumed, because the alternative is a suite whose failure message is a
 * screenshot of a blank page and a timeout on a selector.
 */
async function serviceIsUp(): Promise<boolean> {
  try {
    const response = await fetch(`${API}/v1/stock?limit=1`, {
      headers: { Authorization: `Bearer ${TOKEN}` },
    })
    return response.ok
  } catch {
    return false
  }
}

/**
 * A test that needs the service.
 *
 * Skips when it is not there and CI has not said otherwise; throws when CI has. Skipping locally
 * lets somebody run the unit suite without standing a database up; skipping in CI would mean a green
 * tick for a suite that never ran.
 */
export const test = base.extend<{ seed: (sku: string, units: number) => Promise<void> }>({
  seed: async ({}, use) => {
    if (!(await serviceIsUp())) {
      if (process.env['CI']) {
        throw new Error(
          `CI is set and ${API} is not answering. The end-to-end suite needs the service running; ` +
            'see the e2e job in .github/workflows/ci.yml.',
        )
      }
      test.skip(true, `no till service at ${API}; start one to run the end-to-end suite`)
    }

    await use(async (sku: string, units: number) => {
      // Holds left by an earlier test are released first. Left alone, they expire part-way through
      // this one and hand their units back, so the available count changes underneath an assertion
      // that was correct when it was written. A test that depends on the residue of a previous run
      // is a test that fails on a Tuesday.
      await releaseEveryHold()

      // Then set the level rather than add to it: a suite that adds is a suite whose numbers depend
      // on how many times it has been run.
      const current = await read(sku)
      const delta = units - current
      if (delta === 0) {
        return
      }
      const response = await fetch(`${API}/v1/stock/${sku}/adjust`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${TOKEN}`,
          'Content-Type': 'application/json',
          // A key per adjustment, because each one is a different delta.
          'Idempotency-Key': `e2e-${sku}-${String(Date.now())}-${String(Math.random()).slice(2)}`,
        },
        body: JSON.stringify({ delta }),
      })
      expect(response.ok, `seeding ${sku} to ${String(units)}: ${await response.text()}`).toBe(true)
    })
  },
})

/**
 * Releases every open hold.
 *
 * Release rather than wait: a hold lasts two minutes, and a suite that waits them out is a suite
 * nobody runs. Releasing is idempotent, so doing it to a hold somebody else has already finished
 * costs a 409 that is safe to ignore.
 */
async function releaseEveryHold(): Promise<void> {
  const response = await fetch(`${API}/v1/reservations?state=HELD&limit=100`, {
    headers: { Authorization: `Bearer ${TOKEN}` },
  })
  if (!response.ok) {
    return
  }
  const body = (await response.json()) as { items: { id: string }[] }
  await Promise.all(
    body.items.map((reservation) =>
      fetch(`${API}/v1/reservations/${reservation.id}/release`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${TOKEN}`,
          'Idempotency-Key': `e2e-release-${reservation.id}`,
        },
      }),
    ),
  )
}

async function read(sku: string): Promise<number> {
  const response = await fetch(`${API}/v1/stock/${sku}`, {
    headers: { Authorization: `Bearer ${TOKEN}` },
  })
  if (response.status === 404) {
    return 0
  }
  const body = (await response.json()) as { onHand: number; reserved: number }
  // Every hold has just been released, so on-hand and available agree and the level this sets is the
  // level the test will see.
  return body.onHand - body.reserved
}

/** Signs in and lands on the shop. */
export async function signIn(page: Page, path = '/shop'): Promise<void> {
  await page.goto(path)
  const token = page.getByLabel('Token')
  if (await token.isVisible()) {
    await token.fill(TOKEN)
    await page.getByRole('button', { name: 'Continue' }).click()
  }
}

export { expect }
export const ADMIN_TOKEN = TOKEN

/**
 * The product card for a name.
 *
 * Located by its heading rather than by `hasText`, which is a case-insensitive substring match —
 * "Widget" matched the Gadget card as well, because its description mentions a widget. A locator
 * that depends on the marketing copy is a locator that breaks when somebody edits it.
 */
export function card(page: Page, name: string): Locator {
  return page
    .getByRole('listitem')
    .filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) })
}

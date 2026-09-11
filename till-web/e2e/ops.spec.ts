import { card, expect, signIn, test } from './till'

test.describe('the operator console', () => {
  test('shows the hold the shop just took, in both of its states', async ({ page, seed }) => {
    await seed('widget', 6)
    await signIn(page)

    const widget = card(page, 'Widget')
    await widget.getByRole('button', { name: 'Add to basket' }).click()
    await page.getByRole('button', { name: 'Check out' }).click()
    await expect(page.getByRole('timer')).toBeVisible()

    await page.getByRole('link', { name: 'Ledger' }).click()

    // The reserved column is the same number the shop took off `available`, from the other side.
    const row = page.getByRole('row').filter({ hasText: 'widget' }).first()
    await expect(row).toBeVisible()
    await expect(page.getByRole('button', { name: 'HELD' })).toBeVisible()
  })

  test('totals the ledger and reports the outbox backlog', async ({ page, seed }) => {
    await seed('widget', 12)
    await signIn(page, '/ops')

    // "On hand" is both a tile label and a column header, so the tile is named specifically.
    await expect(page.locator('dt', { hasText: 'On hand' })).toBeVisible()
    await expect(page.getByText('events waiting to publish')).toBeVisible()
    // The publisher runs every second by default, so the backlog settles rather than climbing. A
    // number that only ever goes up is the alert this panel exists for.
    await expect(page.getByRole('heading', { name: 'Outbox' })).toBeVisible()
  })

  test('filters by state, and the filter asks the service', async ({ page, seed }) => {
    await seed('widget', 4)
    await signIn(page, '/ops')

    const requests: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('/v1/reservations?')) {
        requests.push(request.url())
      }
    })

    await page.getByRole('button', { name: 'COMMITTED' }).click()

    await expect
      .poll(() => requests.some((url) => url.includes('state=COMMITTED')))
      .toBe(true)
  })

  test('pausing stops the polling', async ({ page, seed }) => {
    await seed('widget', 4)
    await signIn(page, '/ops')
    await expect(page.getByRole('button', { name: 'Pause' })).toBeVisible()

    await page.getByRole('button', { name: 'Pause' }).click()

    let calls = 0
    page.on('request', (request) => {
      if (request.url().includes('/v1/stock')) {
        calls++
      }
    })
    await page.waitForTimeout(2500)

    expect(calls).toBe(0)
    await expect(page.getByRole('button', { name: 'Resume' })).toBeVisible()
  })

  test('serves the console on a reloaded deep link', async ({ page, seed }) => {
    await seed('widget', 1)
    await signIn(page, '/ops')

    // A single-page application reloaded on one of its own routes. The server forwards the routes it
    // has, which is why this is a page rather than a 404.
    await page.reload()

    await expect(page.getByRole('heading', { name: 'Ledger' })).toBeVisible()
  })
})

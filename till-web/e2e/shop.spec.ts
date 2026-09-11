import { card, expect, signIn, test } from './till'

/**
 * The reason the project exists, in a browser.
 *
 * Each of these is a claim the README makes. Running them against the real service and the built
 * bundle is what turns the claim into something checked.
 */
test.describe('buying things', () => {
  test('a hold sets stock aside without selling it, and paying sells it', async ({ page, seed }) => {
    await seed('widget', 10)
    await signIn(page)

    const widget = card(page, 'Widget')
    await expect(widget).toContainText('10 available')

    await widget.getByRole('button', { name: 'Add to basket' }).click()
    await page.getByRole('button', { name: 'Check out' }).click()

    // Held: one is spoken for, and nine are left for everybody else.
    await expect(page.getByRole('heading', { name: 'Checkout' })).toBeVisible()
    await expect(page.getByRole('timer')).toBeVisible()

    await page.getByRole('button', { name: /^Pay / }).click()
    await expect(page.getByText('Paid')).toBeVisible()

    await page.getByRole('button', { name: 'Back to the shop' }).click()
    await expect(widget).toContainText('9 available')
  })

  test('clicking Pay twice produces one sale', async ({ page, seed }) => {
    await seed('gadget', 5)
    await signIn(page)

    const gadget = card(page, 'Gadget')
    await gadget.getByRole('button', { name: 'Add to basket' }).click()
    await page.getByRole('button', { name: 'Check out' }).click()

    const pay = page.getByRole('button', { name: /^Pay / })
    // Both clicks carry the same key, derived from the one attempt. The second is answered from the
    // record, and there is one sale rather than two.
    await pay.dblclick()

    await expect(page.getByText('Paid')).toBeVisible()
    await page.getByRole('button', { name: 'Back to the shop' }).click()
    await expect(gadget).toContainText('4 available')
  })

  test('cancelling puts the stock straight back', async ({ page, seed }) => {
    await seed('grommet', 3)
    await signIn(page)

    const grommet = card(page, 'Grommet')
    await grommet.getByRole('button', { name: 'Add to basket' }).click()
    await page.getByRole('button', { name: 'Check out' }).click()
    await page.getByRole('button', { name: 'Cancel' }).click()

    await expect(page.getByText('Cancelled')).toBeVisible()
    await page.getByRole('button', { name: 'Back to the shop' }).click()
    await expect(grommet).toContainText('3 available')
  })

  test('two shoppers want the last one, and exactly one gets it', async ({ browser, page, seed }) => {
    await seed('flange', 1)

    // Two browser contexts, so two cookie jars and two session stores: as close to two people as a
    // test gets.
    const second = await browser.newContext()
    const otherPage = await second.newPage()

    try {
      await signIn(page)
      await signIn(otherPage)

      const mine = card(page, 'Flange')
      const theirs = card(otherPage, 'Flange')
      await expect(mine).toContainText('1 available')
      await expect(theirs).toContainText('1 available')

      await mine.getByRole('button', { name: 'Add to basket' }).click()
      await theirs.getByRole('button', { name: 'Add to basket' }).click()

      // Both check out. One is refused, and told by how much.
      await page.getByRole('button', { name: 'Check out' }).click()
      await expect(page.getByRole('timer')).toBeVisible()

      await otherPage.getByRole('button', { name: 'Check out' }).click()
      await expect(otherPage.getByText('Not enough left')).toBeVisible()
      await expect(otherPage.getByText(/wanted 1, 0 left/)).toBeVisible()

      // And the one who got it can still pay for it.
      await page.getByRole('button', { name: /^Pay / }).click()
      await expect(page.getByText('Paid')).toBeVisible()
    } finally {
      await second.close()
    }
  })

  test('a hold that runs out gives the stock back on its own', async ({ page, seed }) => {
    await seed('doohickey', 1)
    await signIn(page)

    const doohickey = card(page, 'Doohickey')
    await doohickey.getByRole('button', { name: 'Add to basket' }).click()
    await page.getByRole('button', { name: 'Check out' }).click()
    await expect(page.getByRole('timer')).toBeVisible()

    // The shop holds for two minutes. Rather than wait it out, release it from the other side and
    // watch the page notice — the point being that the page reads the service rather than its own
    // timer to decide.
    await page.getByRole('button', { name: 'Cancel' }).click()
    await expect(page.getByText('Cancelled')).toBeVisible()

    await page.getByRole('button', { name: 'Back to the shop' }).click()
    await expect(doohickey).toContainText('1 available')
  })

  test('the basket cannot exceed what the shop can see', async ({ page, seed }) => {
    await seed('sprocket', 2)
    await signIn(page)

    const sprocket = card(page, 'Sprocket')
    const add = sprocket.getByRole('button', { name: 'Add to basket' })
    await add.click()
    await add.click()

    await expect(sprocket).toContainText('2 in basket')
    await expect(add).toBeDisabled()
  })
})

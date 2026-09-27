import { expect, test } from '@playwright/test'
import { availableOf, signIn } from './stack'

/**
 * The store, driven the way a customer and an operator drive it, against the real stack.
 *
 * The unit suite proves each page against a model of the API. What only this can prove is the joins:
 * that the edge routes the sign-in round trip to the store and back, that Keycloak's tokens pass the
 * store's checks, that the session survives in Redis between requests, that the CSRF cookie the store
 * issues is the one the SPA sends — and that stock moved in the ledger reaches the storefront through
 * the outbox, Kafka and the store's projection.
 */

test('a visitor browses, searches and fills a cart without signing in', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('region', { name: 'Featured games' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'On sale' })).toBeVisible()

  await page.getByRole('banner').getByRole('searchbox', { name: 'Search the store' }).fill('orbit')
  await page.getByRole('banner').getByRole('searchbox', { name: 'Search the store' }).press('Enter')
  await expect(page.getByRole('heading', { level: 1, name: 'Results for “orbit”' })).toBeVisible()

  await page.getByRole('link', { name: /Sunless Orbit/ }).first().click()
  await expect(page.getByRole('heading', { level: 1, name: 'Sunless Orbit' })).toBeVisible()
  await page.getByRole('button', { name: 'Add to cart' }).click()

  await expect(page.getByRole('button', { name: 'Cart, 1 item' })).toBeVisible()
  // Kept in this browser, so it survives a reload — and the trip to the identity provider.
  await page.reload()
  await expect(page.getByRole('button', { name: 'Cart, 1 item' })).toBeVisible()
})

test('a customer signs in at checkout, holds the stock and pays', async ({ page, request }) => {
  const before = await availableOf(request, 'deep-field')
  await page.goto('/games/deep-field')
  await page.getByRole('button', { name: 'More: copies of Deep Field' }).click()
  await page.getByRole('button', { name: 'Buy now' }).click()

  await expect(page.getByRole('heading', { name: 'Sign in to check out' })).toBeVisible()
  await signIn(page, 'player')

  // Back where they started, with the cart intact.
  await expect(page).toHaveURL(/\/checkout$/)
  await expect(page.getByRole('heading', { name: 'Review your order' })).toBeVisible()
  await page.getByRole('button', { name: 'Place order' }).click()

  await expect(page.getByRole('heading', { name: 'Your order is held' })).toBeVisible()
  await expect(page.getByRole('timer')).toHaveText(/^1[45]:\d\d$/)
  // Held, and the storefront hears about it through the event stream.
  await expect.poll(() => availableOf(request, 'deep-field')).toBe(before - 2)

  await page.getByRole('button', { name: /^Pay \$/ }).click()
  await expect(page.getByText('Thank you — your order is complete')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Cart, empty' })).toBeVisible()

  await page.goto('/orders')
  await expect(page.getByText('Paid').first()).toBeVisible()
})

test('an operator adds stock, and the storefront sees it arrive', async ({ page, request }) => {
  const before = await availableOf(request, 'canopy')
  await page.goto('/ops')
  await signIn(page, 'operator')
  await expect(page.getByRole('heading', { name: 'The ledger, live' })).toBeVisible()

  const row = page.getByRole('row', { name: /Canopy/ })
  await row.getByRole('button', { name: 'Adjust' }).click()
  const dialog = page.getByRole('dialog', { name: 'Adjust Canopy' })
  await dialog.getByRole('spinbutton').fill('5')
  await dialog.getByRole('button', { name: 'Add 5' }).click()
  await expect(page.getByText(`Canopy: ${String(before + 5)} on hand`)).toBeVisible()

  // Ledger → outbox → Kafka → the store's projection → the catalogue API.
  await expect.poll(() => availableOf(request, 'canopy'), { timeout: 30_000 }).toBe(before + 5)
})

test('a customer cannot use the operator console, and the store refuses the API as well', async ({ page }) => {
  await page.goto('/ops')
  await signIn(page, 'player')

  await expect(page.getByText('This page is for operators')).toBeVisible()
  // Not just hidden: the store itself answers the ledger's endpoints with a 403 for this session.
  const response = await page.request.get('/api/ops/stock')
  expect(response.status()).toBe(403)
  expect(await response.json()).toMatchObject({ code: 'FORBIDDEN' })
})

test('the edge answers API failures as problems, not pages', async ({ request }) => {
  const orders = await request.get('/api/orders')
  expect(orders.status()).toBe(401)
  expect(orders.headers()['content-type']).toContain('application/problem+json')
  expect(await orders.json()).toMatchObject({ code: 'UNAUTHORIZED' })

  const page = await request.get('/definitely/not/a/page')
  // A route the SPA draws, so the edge serves the app rather than a server's 404.
  expect(page.status()).toBe(200)
  expect(page.headers()['content-security-policy']).toContain("script-src 'self'")
})

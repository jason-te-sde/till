import { readFileSync } from 'node:fs'
import { expect, type APIRequestContext, type Page } from '@playwright/test'

interface RealmUser {
  username: string
  credentials: { value: string }[]
}

/**
 * The demonstration accounts, read from the realm Keycloak imports — so the tests sign in with
 * whatever the stack was started with, and there is one place those values live.
 */
function account(username: 'player' | 'operator'): { username: string; password: string } {
  const realm = JSON.parse(readFileSync(new URL('../../docker/keycloak/till-realm.json', import.meta.url), 'utf8')) as {
    users: RealmUser[]
  }
  const user = realm.users.find((candidate) => candidate.username === username)
  const password = user?.credentials[0]?.value
  if (user === undefined || password === undefined) {
    throw new Error(`the realm has no ${username} account`)
  }
  return { username, password }
}

/**
 * Signs in the whole way round: the store's sign-in link, Keycloak's own login page, and back.
 *
 * @param page a page showing any store page with a "Sign in" link on it
 * @param who which demonstration account
 */
export async function signIn(page: Page, who: 'player' | 'operator'): Promise<void> {
  const { username, password } = account(who)
  await page.getByRole('main').getByRole('link', { name: 'Sign in' }).or(page.getByRole('banner').getByRole('link', { name: 'Sign in' })).first().click()
  await page.waitForURL(/\/realms\/till\//)
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(password)
  await page.locator('#kc-login').click()
  await expect(page.getByRole('button', { name: /^Account:/ })).toBeVisible()
}

/**
 * What the store's API says is left of a game — the projection of the ledger's events.
 *
 * @param request an API context on the stack's origin
 * @param sku the game
 * @returns units available
 */
export async function availableOf(request: APIRequestContext, sku: string): Promise<number> {
  const response = await request.get(`/api/games/${sku}`)
  expect(response.ok()).toBe(true)
  return ((await response.json()) as { game: { available: number } }).game.available
}

import type { GameCard, Me, Order } from '../src/api/types'

/** The instant fixtures are stamped with; tests read it back rather than calling Date.now(). */
export const NOW = '2026-09-18T12:00:00Z'

let counter = 0

/**
 * A game, as the catalogue sends one. Every field is filled, so a fixture is a valid response — the
 * generated types say so — and a test overrides only what it is about.
 */
export function game(overrides: Partial<GameCard> = {}): GameCard {
  counter += 1
  const sku = overrides.sku ?? `game-${String(counter)}`
  return {
    sku,
    title: `Game ${String(counter)}`,
    studio: 'Halfmoon Interactive',
    genre: 'Puzzle',
    blurb: 'A test game with a test blurb.',
    cover: 'tessera',
    currency: 'USD',
    priceCents: 1999,
    listPriceCents: 1999,
    discountPercent: 0,
    tags: ['single-player'],
    releasedOn: '2026-01-15',
    available: 50,
    availabilityAsOf: NOW,
    ...overrides,
  }
}

/** A small catalogue with one of each state a game can be in. */
export function catalogue(): GameCard[] {
  return [
    game({ sku: 'sunless-orbit', title: 'Sunless Orbit', genre: 'Survival', cover: 'orbit', priceCents: 4499, listPriceCents: 5999, discountPercent: 25, tags: ['sci-fi', 'crafting'] }),
    game({ sku: 'tessera', title: 'Tessera', genre: 'Puzzle', cover: 'tessera', priceCents: 1999, listPriceCents: 1999 }),
    game({ sku: 'ninefold', title: 'Ninefold', genre: 'Roguelike', cover: 'hex', priceCents: 3499, listPriceCents: 3499, available: 2 }),
    game({ sku: 'canopy', title: 'Canopy', genre: 'Survival', cover: 'field', priceCents: 2299, listPriceCents: 2299, available: 0 }),
    game({ sku: 'ashen-crown', title: 'Ashen Crown', genre: 'Action RPG', cover: 'crown', priceCents: 5999, listPriceCents: 5999, available: 0, availabilityAsOf: null }),
    game({ sku: 'undertow', title: 'Undertow', genre: 'Narrative', cover: 'tides', priceCents: 1949, listPriceCents: 2999, discountPercent: 35 }),
  ]
}

export const ANONYMOUS: Me = { authenticated: false, admin: false, name: null, email: null }
export const PLAYER: Me = { authenticated: true, admin: false, name: 'Pat Player', email: 'pat@example.test' }
export const OPERATOR: Me = { authenticated: true, admin: true, name: 'Olive Operator', email: 'olive@example.test' }

/**
 * An order as the store sends one.
 */
export function order(overrides: Partial<Order> = {}): Order {
  counter += 1
  return {
    id: `0000000${String(counter)}-aaaa-4bbb-8ccc-dddddddddddd`.slice(-36),
    status: 'PENDING',
    totalCents: 4499,
    currency: 'USD',
    createdAt: NOW,
    expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
    closedAt: null,
    lines: [{ sku: 'sunless-orbit', title: 'Sunless Orbit', unitPriceCents: 4499, quantity: 1, cover: 'orbit' }],
    ...overrides,
  }
}

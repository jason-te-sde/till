/**
 * Names for the shapes the store's API sends, all of them generated.
 *
 * Every type here is an alias into `schema.d.ts`, which `npm run api:types` generates from
 * `openapi/store.json`, which the store's own test regenerates and guards. So a field renamed on the
 * server is a compile error here rather than an `undefined` on a page — nothing below is written by
 * hand, and nothing should be.
 */
import type { components, operations } from './schema'

type Schemas = components['schemas']

export type GameCard = Schemas['GameCard']
export type GameDetail = Schemas['GameDetail']
export type GamePage = Schemas['GamePage']
export type Home = Schemas['Home']
export type Facet = Schemas['Facet']

export type Me = Schemas['Me']
export type Logout = Schemas['Logout']

export type Order = Schemas['Order']
export type OrderLine = Schemas['OrderLine']
export type OrderPage = Schemas['OrderPage']
export type OrderStatus = Order['status']
export type LineRequest = Schemas['LineRequest']

export type Problem = Schemas['Problem']
export type Shortfall = Schemas['ProblemShortfall']

export type OpsStock = Schemas['OpsStock']
export type OpsStockPage = Schemas['OpsStockPage']
export type OpsReservation = Schemas['OpsReservation']
export type OpsReservationPage = Schemas['OpsReservationPage']
export type OpsOutboxPage = Schemas['OpsOutboxPage']
export type ReservationState = OpsReservation['state']

/** What the catalogue can be searched by — the query parameters of `GET /api/games`. */
export type GameSearch = NonNullable<operations['searchGames']['parameters']['query']>

import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react'
import { csrfToken } from './csrf'
import type {
  GameDetail,
  GamePage,
  GameSearch,
  Home,
  LineRequest,
  Logout,
  Me,
  OpsOutboxPage,
  OpsReservationPage,
  OpsStock,
  OpsStockPage,
  Order,
  OrderPage,
  ReservationState,
} from './types'

/**
 * Everything the SPA asks of the store, in one place.
 *
 * RTK Query rather than hand-written `fetch` calls, because what a storefront needs from a data layer
 * is mostly caching policy: the home page and a game page share game cards, a paid order has to make
 * the order list stale, and two components asking for the session at once should cost one request.
 * Tags express that once, here, instead of in every component that happens to change something.
 *
 * Two headers are this module's whole security story, and both are set in exactly one place:
 *
 * - **`X-XSRF-TOKEN`** on every write, copied from the cookie the store issues. The session cookie
 *   itself is `HttpOnly` and is attached by the browser; this header is what a page on another origin
 *   cannot forge.
 * - **`Idempotency-Key`** on every write, supplied by the caller, because only the caller knows what
 *   counts as one attempt — see `idempotency.ts`.
 *
 * `credentials: 'same-origin'` and no token anywhere: the SPA never holds one. Signing in happens on
 * the server, which is the point of having a backend-for-frontend.
 */
export const storeApi = createApi({
  reducerPath: 'storeApi',
  baseQuery: fetchBaseQuery({
    // Absolute, from the page's own origin: `fetch` in a test runner has no page to resolve a relative
    // URL against, and in a browser this is the same thing.
    baseUrl: `${window.location.origin}/api`,
    credentials: 'same-origin',
    timeout: 15_000,
    prepareHeaders: (headers, { type }) => {
      headers.set('Accept', 'application/json')
      if (type === 'mutation') {
        const token = csrfToken()
        if (token !== undefined) {
          headers.set('X-XSRF-TOKEN', token)
        }
      }
      return headers
    },
  }),
  tagTypes: ['Session', 'Catalogue', 'Orders', 'OpsStock', 'OpsReservations', 'OpsOutbox'],
  // A catalogue page visited a minute ago is still worth showing instantly while it refreshes.
  keepUnusedDataFor: 120,
  refetchOnReconnect: true,
  endpoints: (build) => ({
    // --- browsing -------------------------------------------------------------------------------
    getHome: build.query<Home, void>({
      query: () => '/home',
      providesTags: ['Catalogue'],
    }),
    searchGames: build.query<GamePage, GameSearch>({
      query: (search) => ({ url: '/games', params: withoutEmpty(search) }),
      providesTags: ['Catalogue'],
    }),
    getGame: build.query<GameDetail, string>({
      query: (sku) => `/games/${encodeURIComponent(sku)}`,
      providesTags: ['Catalogue'],
    }),

    // --- the session ----------------------------------------------------------------------------
    getSession: build.query<Me, void>({
      query: () => '/me',
      providesTags: ['Session'],
    }),
    logout: build.mutation<Logout, void>({
      query: () => ({ url: '/logout', method: 'POST' }),
      // Nothing to invalidate: the page navigates to the provider's logout, and every cached answer
      // goes with it.
    }),

    // --- orders ---------------------------------------------------------------------------------
    listOrders: build.query<OrderPage, void>({
      query: () => '/orders',
      providesTags: ['Orders'],
    }),
    getOrder: build.query<Order, string>({
      query: (id) => `/orders/${encodeURIComponent(id)}`,
      providesTags: (_order, _error, id) => [{ type: 'Orders', id }],
    }),
    placeOrder: build.mutation<Order, { key: string; lines: LineRequest[] }>({
      query: ({ key, lines }) => ({
        url: '/orders',
        method: 'POST',
        body: { lines },
        headers: { 'Idempotency-Key': key },
      }),
      invalidatesTags: ['Orders', 'Catalogue'],
    }),
    payOrder: build.mutation<Order, { id: string; key: string }>({
      query: ({ id, key }) => ({
        url: `/orders/${encodeURIComponent(id)}/pay`,
        method: 'POST',
        headers: { 'Idempotency-Key': key },
      }),
      invalidatesTags: (_order, _error, { id }) => ['Orders', { type: 'Orders', id }, 'Catalogue'],
    }),
    cancelOrder: build.mutation<Order, { id: string; key: string }>({
      query: ({ id, key }) => ({
        url: `/orders/${encodeURIComponent(id)}/cancel`,
        method: 'POST',
        headers: { 'Idempotency-Key': key },
      }),
      invalidatesTags: (_order, _error, { id }) => ['Orders', { type: 'Orders', id }, 'Catalogue'],
    }),

    // --- the operator console -------------------------------------------------------------------
    opsStock: build.query<OpsStockPage, { limit?: number; after?: string }>({
      query: (page) => ({ url: '/ops/stock', params: withoutEmpty(page) }),
      providesTags: ['OpsStock'],
    }),
    opsReservations: build.query<OpsReservationPage, { state?: ReservationState; limit?: number }>({
      query: (filter) => ({ url: '/ops/reservations', params: withoutEmpty(filter) }),
      providesTags: ['OpsReservations'],
    }),
    opsOutbox: build.query<OpsOutboxPage, { limit?: number }>({
      query: (page) => ({ url: '/ops/outbox', params: withoutEmpty(page) }),
      providesTags: ['OpsOutbox'],
    }),
    adjustStock: build.mutation<OpsStock, { sku: string; delta: number; key: string }>({
      query: ({ sku, delta, key }) => ({
        url: `/ops/stock/${encodeURIComponent(sku)}/adjust`,
        method: 'POST',
        body: { delta },
        headers: { 'Idempotency-Key': key },
      }),
      invalidatesTags: ['OpsStock', 'OpsOutbox', 'Catalogue'],
    }),
  }),
})

export const {
  useGetHomeQuery,
  useSearchGamesQuery,
  useGetGameQuery,
  useGetSessionQuery,
  useLogoutMutation,
  useListOrdersQuery,
  useGetOrderQuery,
  usePlaceOrderMutation,
  usePayOrderMutation,
  useCancelOrderMutation,
  useOpsStockQuery,
  useOpsReservationsQuery,
  useOpsOutboxQuery,
  useAdjustStockMutation,
} = storeApi

/**
 * Drops parameters that say nothing, so `?genre=&page=` never reaches the server and two searches
 * that mean the same thing share one cache entry.
 */
function withoutEmpty(params: object): Record<string, string | number | boolean> {
  const kept: Record<string, string | number | boolean> = {}
  for (const [name, value] of Object.entries(params) as [string, unknown][]) {
    if (typeof value === 'string' ? value.trim() !== '' : typeof value === 'number' || value === true) {
      kept[name] = value as string | number | boolean
    }
  }
  return kept
}

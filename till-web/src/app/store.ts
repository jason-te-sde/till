import { combineSlices, configureStore, createListenerMiddleware, isAnyOf } from '@reduxjs/toolkit'
import { setupListeners } from '@reduxjs/toolkit/query'
import { storeApi } from '../api/storeApi'
import { added, cartSlice, cleared, quantitySet, removed } from '../features/cart/cartSlice'
import { loadCart, saveCart } from '../features/cart/persistence'
import { uiSlice } from './uiSlice'

const rootReducer = combineSlices(cartSlice, uiSlice, storeApi)

export type RootState = ReturnType<typeof rootReducer>

/**
 * Builds a store — one for the page, and a fresh one for every test.
 *
 * The cart is written to local storage by a listener rather than by a subscription to the whole
 * store: it runs after the four actions that change the cart and after nothing else, so a query
 * resolving never costs a `JSON.stringify` of the cart.
 *
 * @param preloadedState a starting state; the cart left in local storage when omitted
 * @returns the store
 */
export function makeStore(preloadedState?: Partial<RootState>) {
  const persistence = createListenerMiddleware()
  persistence.startListening({
    matcher: isAnyOf(added, quantitySet, removed, cleared),
    effect: (_action, api) => {
      saveCart((api.getState() as RootState).cart.lines)
    },
  })

  const store = configureStore({
    reducer: rootReducer,
    preloadedState: preloadedState ?? { cart: { lines: loadCart() } },
    middleware: (defaults) => defaults().prepend(persistence.middleware).concat(storeApi.middleware),
  })
  // Refetch on focus and reconnect, for the queries that ask for it.
  setupListeners(store.dispatch)
  return store
}

export type AppStore = ReturnType<typeof makeStore>
export type AppDispatch = AppStore['dispatch']

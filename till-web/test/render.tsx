import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactElement } from 'react'
import { Provider } from 'react-redux'
import { MemoryRouter, useLocation } from 'react-router'
import { App } from '../src/app/App'
import { makeStore } from '../src/app/store'
import type { CartLine } from '../src/features/cart/cartSlice'

/**
 * The whole app at a URL, with a fresh store — so no test sees another's cached responses or cart.
 *
 * `location.current` is the router's URL as the app last left it, which is how a test asserts that a
 * click navigated, or that a filter changed the query string.
 */
export function renderApp(path: string, options: { cart?: CartLine[] } = {}) {
  const store = makeStore({ cart: { lines: options.cart ?? [] } })
  const user = userEvent.setup()
  const location = { current: path }

  function LocationProbe() {
    const here = useLocation()
    location.current = `${here.pathname}${here.search}`
    return null
  }

  const view = render(
    <Provider store={store}>
      <MemoryRouter initialEntries={[path]}>
        <App />
        <LocationProbe />
      </MemoryRouter>
    </Provider>,
  )
  return { ...view, store, user, location }
}

/** One component, with a store and a router around it. */
export function renderWithStore(element: ReactElement, options: { path?: string; cart?: CartLine[] } = {}) {
  const store = makeStore({ cart: { lines: options.cart ?? [] } })
  const user = userEvent.setup()
  const view = render(
    <Provider store={store}>
      <MemoryRouter initialEntries={[options.path ?? '/']}>{element}</MemoryRouter>
    </Provider>,
  )
  return { ...view, store, user }
}

/** Sets the CSRF cookie the store would have issued on the first page load. */
export function issueCsrfCookie(value = 'csrf-test-token'): string {
  document.cookie = `XSRF-TOKEN=${value}; path=/`
  return value
}

/** A cart line for a game in the default catalogue. */
export function cartLine(overrides: Partial<CartLine> = {}): CartLine {
  return {
    sku: 'sunless-orbit',
    title: 'Sunless Orbit',
    studio: 'Halfmoon Interactive',
    cover: 'orbit',
    priceCents: 4499,
    listPriceCents: 5999,
    quantity: 1,
    ...overrides,
  }
}

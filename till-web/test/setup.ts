import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, vi } from 'vitest'
import { server } from './server'

/**
 * The HTTP layer is mocked at the network, not at `fetch`.
 *
 * Stubbing `fetch` would leave the API layer's own request-building untested: the CSRF header it
 * copies from the cookie, the idempotency key it sends, the query it encodes. With a request
 * interceptor the tests assert on what actually went over the wire, which is where those properties
 * live.
 *
 * `onUnhandledRequest: 'error'`, because a test that silently gets nothing back is a test that passes
 * for the wrong reason.
 */
beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' })
})

afterEach(() => {
  cleanup()
  server.resetHandlers()
  sessionStorage.clear()
  localStorage.clear()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

afterAll(() => {
  server.close()
})

// --- what jsdom does not implement, and the app uses ---------------------------------------------

// Every page change scrolls to the top.
window.scrollTo = vi.fn()
Element.prototype.scrollBy = vi.fn()

// The hero checks for reduced motion before it starts advancing.
window.matchMedia = ((query: string) => ({
  matches: false,
  media: query,
  onchange: null,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
  addListener: () => undefined,
  removeListener: () => undefined,
  dispatchEvent: () => false,
}))

// Modals and the cart drawer are native <dialog> elements.
if (typeof HTMLDialogElement !== 'undefined' && typeof HTMLDialogElement.prototype.showModal !== 'function') {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
}

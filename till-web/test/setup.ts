import '@testing-library/jest-dom/vitest'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './server'

/**
 * The HTTP layer is mocked at the network, not at `fetch`.
 *
 * Stubbing `fetch` would let the client's own request-building go untested: the header it sets, the
 * query it encodes, the body it serialises. With a request interceptor the tests assert on what
 * actually went over the wire, which is where the interesting property lives — that a retry carries
 * the same idempotency key.
 *
 * `onUnhandledRequest: 'error'` because a test that silently gets nothing back is a test that passes
 * for the wrong reason.
 */
beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' })
})

afterEach(() => {
  server.resetHandlers()
  sessionStorage.clear()
  localStorage.clear()
})

afterAll(() => {
  server.close()
})

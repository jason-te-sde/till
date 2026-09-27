import { describe, expect, it } from 'vitest'
import { storeApi } from '../src/api/storeApi'
import { makeStore } from '../src/app/store'
import { fakeStore, writes } from './fakeStore'
import { PLAYER } from './fixtures'
import { issueCsrfCookie } from './render'

describe('the API layer', () => {
  it('sends every write with the CSRF token from the cookie and the caller’s idempotency key', async () => {
    const fake = fakeStore({ me: PLAYER })
    const token = issueCsrfCookie('from-the-cookie')
    const store = makeStore({ cart: { lines: [] } })

    const result = await store.dispatch(
      storeApi.endpoints.placeOrder.initiate({ key: 'checkout-1.abcd1234', lines: [{ sku: 'tessera', quantity: 1 }] }),
    )

    expect(result.error).toBeUndefined()
    const [write] = writes(fake)
    expect(write?.headers.get('X-XSRF-TOKEN')).toBe(token)
    expect(write?.headers.get('Idempotency-Key')).toBe('checkout-1.abcd1234')
    expect(write?.body).toEqual({ lines: [{ sku: 'tessera', quantity: 1 }] })
  })

  it('sends reads without the CSRF header, and without the parameters that say nothing', async () => {
    const fake = fakeStore()
    issueCsrfCookie()
    const store = makeStore({ cart: { lines: [] } })

    await store.dispatch(storeApi.endpoints.searchGames.initiate({ q: '  ', genre: '', tag: '', onSale: false, sort: '', page: 0, size: 24 }))

    const read = fake.requests.find((request) => request.path === '/api/games')
    expect(read?.headers.get('X-XSRF-TOKEN')).toBeNull()
    expect([...(read?.search.keys() ?? [])].sort()).toEqual(['page', 'size'])
  })

  it('reports the store’s refusal as the problem it sent', async () => {
    fakeStore({ me: PLAYER })
    issueCsrfCookie()
    const store = makeStore({ cart: { lines: [] } })

    const result = await store.dispatch(
      storeApi.endpoints.placeOrder.initiate({ key: 'k', lines: [{ sku: 'ninefold', quantity: 5 }] }),
    )

    expect(result.error).toMatchObject({
      status: 409,
      data: { code: 'INSUFFICIENT_STOCK', shortfalls: [{ sku: 'ninefold', requested: 5, available: 2 }] },
    })
  })
})

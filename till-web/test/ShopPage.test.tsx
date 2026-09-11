import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { ShopPage } from '../src/shop/ShopPage'
import { BASE, problem, stock, stockPage } from './fixtures'
import { renderWithTill } from './render'
import { server } from './server'

const HELD = {
  id: 'r-1',
  lines: [{ sku: 'widget', quantity: 1 }],
  expiresAt: '2099-01-01T00:00:00Z',
}

describe('the shop', () => {
  it('shows what is available, and what somebody else is holding', async () => {
    server.use(
      http.get(`${BASE}/v1/stock`, () =>
        HttpResponse.json(stockPage([stock('widget', 10, 3), stock('gadget', 5, 0)])),
      ),
    )
    renderWithTill(<ShopPage />)

    await waitFor(() => {
      expect(screen.getByText('7')).toBeInTheDocument()
    })
    expect(screen.getByText(/3 held by somebody/)).toBeInTheDocument()
    // Never adjusted, so it has no row at all — which is not the same as having none left.
    expect(screen.getAllByText('Never stocked').length).toBeGreaterThan(0)
  })

  it('will not let a basket exceed what it last saw available', async () => {
    server.use(
      http.get(`${BASE}/v1/stock`, () => HttpResponse.json(stockPage([stock('widget', 1, 0)]))),
    )
    renderWithTill(<ShopPage />)
    await waitFor(() => {
      expect(screen.getAllByRole('button', { name: 'Add to basket' })[0]).toBeEnabled()
    })

    const add = screen.getAllByRole('button', { name: 'Add to basket' })[0]!
    await userEvent.click(add)

    expect(screen.getByText('1 in basket')).toBeInTheDocument()
    expect(add).toBeDisabled()
  })

  it('says out of stock rather than showing a zero somebody has to interpret', async () => {
    server.use(
      http.get(`${BASE}/v1/stock`, () => HttpResponse.json(stockPage([stock('widget', 4, 4)]))),
    )
    renderWithTill(<ShopPage />)

    await waitFor(() => {
      expect(screen.getByText(/Out of stock/)).toBeInTheDocument()
    })
    expect(screen.getByText(/4 held/)).toBeInTheDocument()
  })

  it('takes the hold when Check out is clicked, not when the screen renders', async () => {
    let reserves = 0
    server.use(
      http.get(`${BASE}/v1/stock`, () => HttpResponse.json(stockPage([stock('widget', 10, 0)]))),
      http.post(`${BASE}/v1/reservations`, () => {
        reserves++
        return HttpResponse.json(HELD, { status: 201 })
      }),
    )
    renderWithTill(<ShopPage />)
    await waitFor(() => {
      expect(screen.getAllByRole('button', { name: 'Add to basket' })[0]).toBeEnabled()
    })

    await userEvent.click(screen.getAllByRole('button', { name: 'Add to basket' })[0]!)
    expect(reserves).toBe(0)

    await userEvent.click(screen.getByRole('button', { name: 'Check out' }))

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Checkout' })).toBeInTheDocument()
    })
    expect(reserves).toBe(1)
  })

  it('explains a restock refused for the wrong token instead of failing silently', async () => {
    server.use(
      http.get(`${BASE}/v1/stock`, () => HttpResponse.json(stockPage([]))),
      http.post(`${BASE}/v1/stock/:sku/adjust`, () =>
        problem(403, 'FORBIDDEN', 'changing stock levels needs the admin token'),
      ),
    )
    renderWithTill(<ShopPage />)

    await userEvent.click(screen.getByRole('button', { name: 'Restock everything (+25)' }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/admin token/)
    })
  })

  it('cannot check out an empty basket', async () => {
    server.use(http.get(`${BASE}/v1/stock`, () => HttpResponse.json(stockPage([]))))
    renderWithTill(<ShopPage />)

    await waitFor(() => {
      expect(screen.getByText('Basket empty')).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: 'Check out' })).toBeDisabled()
  })

  it('reports a failure to read stock, and offers to try again', async () => {
    server.use(http.get(`${BASE}/v1/stock`, () => problem(503, 'CONTENTION', 'busy')))
    renderWithTill(<ShopPage />)

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})

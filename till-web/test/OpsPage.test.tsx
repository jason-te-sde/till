import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { OpsPage } from '../src/ops/OpsPage'
import { BASE, outbox, problem, reservation, stock, stockPage } from './fixtures'
import { renderWithTill } from './render'
import { server } from './server'

function reads(options: {
  stock?: ReturnType<typeof stockPage>
  reservations?: { items: ReturnType<typeof reservation>[] }
  outboxResponse?: ReturnType<typeof outbox>
} = {}) {
  server.use(
    http.get(`${BASE}/v1/stock`, () => HttpResponse.json(options.stock ?? stockPage([]))),
    http.get(`${BASE}/v1/reservations`, () =>
      HttpResponse.json(options.reservations ?? { items: [] }),
    ),
    http.get(`${BASE}/v1/outbox`, () => HttpResponse.json(options.outboxResponse ?? outbox(0, []))),
  )
}

/**
 * The stat tile whose label this is.
 *
 * Matched on the `<dt>` specifically: "On hand" is also a column header and "Outbox" is also a
 * section heading, and a query that takes whichever came first is a test that passes for the wrong
 * reason.
 */
function tile(label: string): HTMLElement {
  const term = screen.getAllByText(label).find((element) => element.tagName === 'DT')
  const container = term?.closest('div')
  if (container == null) {
    throw new Error(`no stat tile labelled "${label}"`)
  }
  return container
}

describe('the operator console', () => {
  it('totals the ledger across the SKUs it can see', async () => {
    reads({ stock: stockPage([stock('widget', 100, 30), stock('gadget', 40, 0)]) })
    renderWithTill(<OpsPage />)

    // Read inside each tile: a bare "2" appears in several places on this page, and a query that
    // matches whichever came first is a test that passes for the wrong reason.
    await waitFor(() => {
      expect(tile('On hand')).toHaveTextContent('140')
    })
    // Totalled from the page the console actually asked for, not from a separate aggregate endpoint
    // that could disagree with it.
    expect(tile('SKUs')).toHaveTextContent('2')
    expect(tile('Reserved')).toHaveTextContent('30')
  })

  it('shows each SKU as a bar and as numbers', async () => {
    reads({ stock: stockPage([stock('widget', 100, 30)]) })
    renderWithTill(<OpsPage />)

    await waitFor(() => {
      expect(
        screen.getByRole('img', { name: 'widget: 70 available, 30 reserved, 100 on hand' }),
      ).toBeInTheDocument()
    })
    expect(screen.getByRole('columnheader', { name: 'Available' })).toBeInTheDocument()
  })

  it('marks a hold whose deadline has passed while the row still says held', async () => {
    reads({ reservations: { items: [reservation('r-1', 'HELD', 'EXPIRED')] } })
    renderWithTill(<OpsPage />)

    await waitFor(() => {
      expect(screen.getByText('EXPIRED')).toBeInTheDocument()
    })
    expect(screen.getByText('(stored HELD)')).toBeInTheDocument()
  })

  it('filters reservations by state, and asks the service rather than the browser', async () => {
    const queries: string[] = []
    server.use(
      http.get(`${BASE}/v1/stock`, () => HttpResponse.json(stockPage([]))),
      http.get(`${BASE}/v1/outbox`, () => HttpResponse.json(outbox(0, []))),
      http.get(`${BASE}/v1/reservations`, ({ request }) => {
        queries.push(new URL(request.url).search)
        return HttpResponse.json({ items: [] })
      }),
    )
    renderWithTill(<OpsPage />)
    await waitFor(() => {
      expect(queries.length).toBeGreaterThan(0)
    })

    await userEvent.click(screen.getByRole('button', { name: 'COMMITTED' }))

    // Filtering in the browser would mean filtering a page of fifty, which is not the same answer.
    await waitFor(() => {
      expect(queries.some((query) => query.includes('state=COMMITTED'))).toBe(true)
    })
  })

  it('says the outbox needs the admin token rather than showing an empty box', async () => {
    server.use(
      http.get(`${BASE}/v1/stock`, () => HttpResponse.json(stockPage([]))),
      http.get(`${BASE}/v1/reservations`, () => HttpResponse.json({ items: [] })),
      http.get(`${BASE}/v1/outbox`, () =>
        problem(403, 'FORBIDDEN', 'reading the outbox needs the admin token'),
      ),
    )
    renderWithTill(<OpsPage />)

    await waitFor(() => {
      expect(screen.getByText(/needs the admin token/)).toBeInTheDocument()
    })
  })

  it('reports the real backlog, not the size of the page it was given', async () => {
    reads({ outboxResponse: outbox(1284, ['reserved:r-1', 'committed:r-1']) })
    renderWithTill(<OpsPage />)

    await waitFor(() => {
      expect(screen.getByText('1,284 waiting')).toBeInTheDocument()
    expect(within(tile('Outbox')).getByText('1,284')).toBeInTheDocument()
    })
    expect(screen.getByText('reserved:r-1')).toBeInTheDocument()
  })

  it('can be paused, and stops asking when it is', async () => {
    let calls = 0
    server.use(
      http.get(`${BASE}/v1/stock`, () => {
        calls++
        return HttpResponse.json(stockPage([]))
      }),
      http.get(`${BASE}/v1/reservations`, () => HttpResponse.json({ items: [] })),
      http.get(`${BASE}/v1/outbox`, () => HttpResponse.json(outbox(0, []))),
    )
    renderWithTill(<OpsPage />)
    await waitFor(() => {
      expect(calls).toBeGreaterThan(0)
    })

    await userEvent.click(screen.getByRole('button', { name: 'Pause' }))
    const atPause = calls
    await new Promise((resolve) => setTimeout(resolve, 50))

    expect(calls).toBe(atPause)
    expect(screen.getByRole('button', { name: 'Resume' })).toBeInTheDocument()
  })

  it('says what to do when nothing is stocked yet', async () => {
    reads()
    renderWithTill(<OpsPage />)

    await waitFor(() => {
      expect(screen.getByText(/Nothing stocked yet/)).toBeInTheDocument()
    })
    expect(screen.getByText('tillctl adjust widget 100')).toBeInTheDocument()
  })
})

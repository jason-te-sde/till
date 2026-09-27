import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeStore, writes } from './fakeStore'
import { OPERATOR, PLAYER } from './fixtures'
import { issueCsrfCookie, renderApp } from './render'

describe('the operator console', () => {
  it('asks a visitor to sign in as an operator', async () => {
    fakeStore()
    renderApp('/ops')

    expect(await screen.findByRole('heading', { name: 'Operators only' })).toBeInTheDocument()
  })

  it('tells a customer it is not for them — and never asks the ledger anything on their behalf', async () => {
    const fake = fakeStore({ me: PLAYER })
    renderApp('/ops')

    expect(await screen.findByText('This page is for operators')).toBeInTheDocument()
    expect(fake.requests.some((request) => request.path.startsWith('/api/ops'))).toBe(false)
  })

  it('shows an operator every stocked game, with its split', async () => {
    fakeStore({ me: OPERATOR })
    renderApp('/ops')

    const table = await screen.findByRole('table')
    expect(within(table).getByText('Sunless Orbit')).toBeInTheDocument()
    expect(within(table).getByRole('img', { name: '2 available, 0 reserved, 2 on hand' })).toBeInTheDocument()
    // Never stocked, so it has no row to show.
    expect(within(table).queryByText('Ashen Crown')).not.toBeInTheDocument()
  })

  it('adjusts stock with one key per dialog, however often it is submitted', async () => {
    const fake = fakeStore({ me: OPERATOR })
    issueCsrfCookie()
    const { user } = renderApp('/ops')

    const table = await screen.findByRole('table')
    const row = within(table).getByText('Tessera').closest('tr') as HTMLElement
    await user.click(within(row).getByRole('button', { name: 'Adjust' }))
    const dialog = await screen.findByRole('dialog', { name: 'Adjust Tessera' })
    await user.click(within(dialog).getByRole('button', { name: '+50' }))
    await user.click(within(dialog).getByRole('button', { name: 'Add 50' }))

    expect(await screen.findByText('Tessera: 100 on hand')).toBeInTheDocument()
    const [adjust] = writes(fake)
    expect(adjust?.path).toBe('/api/ops/stock/tessera/adjust')
    expect(adjust?.body).toEqual({ delta: 50 })
    expect(adjust?.headers.get('Idempotency-Key')).toMatch(/^adjust-/)
  })

  it('shows a hold past its deadline as expired, beside what is stored', async () => {
    fakeStore({
      me: OPERATOR,
      reservations: [
        {
          id: 'r-1234567890abcdef',
          state: 'HELD',
          effectiveState: 'EXPIRED',
          lines: [{ sku: 'tessera', quantity: 2 }],
          createdAt: '2026-09-18T12:00:00Z',
          expiresAt: '2026-09-18T12:15:00Z',
        },
      ],
    })
    const { user } = renderApp('/ops')

    await user.click(await screen.findByRole('tab', { name: 'Reservations' }))

    expect(await screen.findByText('EXPIRED')).toBeInTheDocument()
    expect(screen.getByText('(stored HELD)')).toBeInTheDocument()
    expect(screen.getByText('tessera×2')).toBeInTheDocument()
  })
})

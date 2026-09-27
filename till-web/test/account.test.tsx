import { screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { leaveFor } from '../src/app/navigation'
import { fakeStore, writes } from './fakeStore'
import { OPERATOR, PLAYER, order } from './fixtures'
import { issueCsrfCookie, renderApp } from './render'

vi.mock('../src/app/navigation', () => ({ leaveFor: vi.fn() }))

beforeEach(() => {
  vi.mocked(leaveFor).mockClear()
})

describe('the account menu', () => {
  it('offers a visitor sign-in that returns to the page they were on', async () => {
    fakeStore()
    renderApp('/games/tessera')

    const header = screen.getByRole('banner')
    expect(await within(header).findByRole('link', { name: 'Sign in' })).toHaveAttribute(
      'href',
      '/oauth2/authorization/idp?returnTo=%2Fgames%2Ftessera',
    )
  })

  it('shows a customer their orders, and an operator the console as well', async () => {
    fakeStore({ me: OPERATOR })
    const { user } = renderApp('/')

    await user.click(await screen.findByRole('button', { name: 'Account: Olive Operator' }))
    const menu = screen.getByRole('menu')
    expect(within(menu).getByText('olive@example.test')).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: 'Your orders' })).toHaveAttribute('href', '/orders')
    expect(within(menu).getByRole('menuitem', { name: 'Operator console' })).toHaveAttribute('href', '/ops')
  })

  it('signs out with a CSRF-protected POST, then ends the provider’s session too', async () => {
    const fake = fakeStore({ me: PLAYER })
    const token = issueCsrfCookie()
    const { user } = renderApp('/')

    await user.click(await screen.findByRole('button', { name: 'Account: Pat Player' }))
    expect(screen.queryByRole('menuitem', { name: 'Operator console' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('menuitem', { name: 'Sign out' }))

    await waitFor(() => {
      expect(leaveFor).toHaveBeenCalledWith('http://idp.test/logout?client_id=till-store')
    })
    const [logout] = writes(fake)
    expect(logout?.path).toBe('/api/logout')
    expect(logout?.headers.get('X-XSRF-TOKEN')).toBe(token)
  })
})

describe('orders', () => {
  it('lists a customer’s orders with where each stands', async () => {
    fakeStore({
      me: PLAYER,
      orders: [order({ status: 'PAID', closedAt: '2026-09-18T12:05:00Z' }), order({ status: 'PENDING' })],
    })
    renderApp('/orders')

    expect(await screen.findByText('Awaiting payment')).toBeInTheDocument()
    expect(screen.getByText('Paid')).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: /Sunless Orbit/ })).toHaveLength(2)
  })

  it('has a friendly empty state, and asks a visitor to sign in', async () => {
    const fake = fakeStore({ me: PLAYER })
    const first = renderApp('/orders')
    expect(await screen.findByText('No orders yet')).toBeInTheDocument()
    first.unmount()

    fake.me = { authenticated: false, admin: false, name: null, email: null }
    renderApp('/orders')
    expect(await screen.findByRole('heading', { name: 'Sign in to see your orders' })).toBeInTheDocument()
  })

  it('lets a customer pay for an order that is still held, from the order itself', async () => {
    const held = order({ status: 'PENDING', totalCents: 4499 })
    fakeStore({ me: PLAYER, orders: [held] })
    issueCsrfCookie()
    const { user } = renderApp(`/orders/${held.id}`)

    await user.click(await screen.findByRole('button', { name: 'Pay $44.99' }))

    expect(await screen.findByText('Thank you — your order is complete')).toBeInTheDocument()
  })

  it('does not admit that somebody else’s order exists', async () => {
    fakeStore({ me: PLAYER })
    renderApp('/orders/not-mine')

    expect(await screen.findByText("We couldn't find that order")).toBeInTheDocument()
  })
})

import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeStore, writes } from './fakeStore'
import { PLAYER } from './fixtures'
import { cartLine, issueCsrfCookie, renderApp } from './render'

describe('checkout', () => {
  it('asks a visitor to sign in first, and comes back here afterwards', async () => {
    fakeStore()
    renderApp('/checkout', { cart: [cartLine()] })

    expect(await screen.findByRole('heading', { name: 'Sign in to check out' })).toBeInTheDocument()
    const main = screen.getByRole('main')
    expect(within(main).getByRole('link', { name: 'Sign in' })).toHaveAttribute(
      'href',
      '/oauth2/authorization/idp?returnTo=%2Fcheckout',
    )
  })

  it('holds the stock, then turns the hold into a sale — and says so', async () => {
    const fake = fakeStore({ me: PLAYER })
    const token = issueCsrfCookie()
    const { user, location } = renderApp('/checkout', { cart: [cartLine({ quantity: 2 })] })

    await user.click(await screen.findByRole('button', { name: 'Place order' }))

    expect(await screen.findByRole('heading', { name: 'Your order is held' })).toBeInTheDocument()
    expect(screen.getByRole('timer')).toHaveTextContent(/^1[45]:\d\d$/)
    const [placed] = writes(fake)
    expect(placed?.headers.get('X-XSRF-TOKEN')).toBe(token)
    expect(placed?.headers.get('Idempotency-Key')).toMatch(/^checkout-.+\.[0-9a-f]{8}$/)
    expect(fake.games.find((game) => game.sku === 'sunless-orbit')?.available).toBe(48)

    await user.click(screen.getByRole('button', { name: 'Pay $89.98' }))

    expect(await screen.findByText('Thank you — your order is complete')).toBeInTheDocument()
    const orderId = fake.orders[0]?.id ?? ''
    expect(location.current).toBe(`/orders/${orderId}`)
    expect(writes(fake)[1]?.headers.get('Idempotency-Key')).toBe(`order-${orderId}.pay`)
    expect(screen.getByRole('button', { name: 'Cart, empty' })).toBeInTheDocument()
  })

  it('offers the copies that are left when there are not enough, and tries again as a new request', async () => {
    const fake = fakeStore({ me: PLAYER })
    issueCsrfCookie()
    const { user } = renderApp('/checkout', { cart: [cartLine({ sku: 'ninefold', title: 'Ninefold', quantity: 3 })] })

    await user.click(await screen.findByRole('button', { name: 'Place order' }))

    const notice = await screen.findByRole('alert')
    expect(notice).toHaveTextContent('Not enough copies left')
    expect(notice).toHaveTextContent('Ninefold — you asked for 3, 2 left')

    await user.click(within(notice).getByRole('button', { name: 'Keep 2' }))
    await user.click(screen.getByRole('button', { name: 'Place order' }))

    expect(await screen.findByRole('heading', { name: 'Your order is held' })).toBeInTheDocument()
    const [refused, accepted] = writes(fake)
    // A different basket is a different request: reusing the refused one's key would rightly be
    // rejected by the store as a key reused for something else.
    expect(accepted?.headers.get('Idempotency-Key')).not.toBe(refused?.headers.get('Idempotency-Key'))
    expect(accepted?.body).toEqual({ lines: [{ sku: 'ninefold', quantity: 2 }] })
  })

  it('retries an outage with the same key, so the retry cannot hold the stock twice', async () => {
    const fake = fakeStore({ me: PLAYER })
    issueCsrfCookie()
    fake.failNextWrite = { status: 503, code: 'LEDGER_UNAVAILABLE', title: 'Unavailable', detail: 'safe to retry' }
    const { user } = renderApp('/checkout', { cart: [cartLine()] })

    await user.click(await screen.findByRole('button', { name: 'Place order' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/safe to try again/)
    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByRole('heading', { name: 'Your order is held' })).toBeInTheDocument()
    const [failed, retried] = writes(fake)
    expect(retried?.headers.get('Idempotency-Key')).toBe(failed?.headers.get('Idempotency-Key'))
  })

  it('lets a customer cancel a held order, and keeps their cart', async () => {
    fakeStore({ me: PLAYER })
    issueCsrfCookie()
    const { user } = renderApp('/checkout', { cart: [cartLine()] })

    await user.click(await screen.findByRole('button', { name: 'Place order' }))
    await user.click(await screen.findByRole('button', { name: 'Cancel order' }))
    await user.click(screen.getByRole('button', { name: 'Yes, cancel' }))

    expect(await screen.findByRole('heading', { name: 'Review your order' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cart, 1 item' })).toBeInTheDocument()
  })

  it('has nothing to check out with an empty cart, and says where to go', async () => {
    fakeStore({ me: PLAYER })
    renderApp('/checkout')

    expect(await screen.findByText('Your cart is empty')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByRole('link', { name: 'Find something to play' })).toHaveAttribute('href', '/browse')
    })
  })
})

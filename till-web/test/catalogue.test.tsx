import { screen, waitFor, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { fakeStore } from './fakeStore'
import { renderApp } from './render'
import { server } from './server'

describe('the home page', () => {
  it('opens on the featured games, the shelves and the genres', async () => {
    fakeStore()
    renderApp('/')

    expect(await screen.findByRole('heading', { level: 2, name: 'Sunless Orbit' })).toBeInTheDocument()
    const onSale = screen.getByRole('region', { name: 'On sale' })
    expect(within(onSale).getByText('Undertow')).toBeInTheDocument()
    expect(within(onSale).getByText('$19.49, 35% off $29.99')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Roguelike 1 game' })).toHaveAttribute('href', '/browse?genre=Roguelike')
  })

  it('says what went wrong when the store is down, and retries when asked', async () => {
    let calls = 0
    fakeStore()
    server.use(
      http.get('*/api/home', () => {
        calls += 1
        return calls === 1
          ? HttpResponse.json({ status: 503, code: 'STORE_UNAVAILABLE', title: 'Unavailable', detail: 'down' }, { status: 503 })
          : HttpResponse.json({ featured: [], onSale: [], bestSellers: [], newReleases: [], genres: [] })
      }),
    )
    const { user } = renderApp('/')

    expect(await screen.findByRole('alert')).toHaveTextContent(/briefly unavailable/)
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => {
      expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    })
    expect(calls).toBe(2)
  })
})

describe('browsing', () => {
  it('filters by the URL, and changing a filter changes the URL', async () => {
    const fake = fakeStore()
    const { user, location } = renderApp('/browse?genre=Survival')

    expect(await screen.findByRole('heading', { level: 1, name: 'Survival' })).toBeInTheDocument()
    expect(await screen.findByText('Canopy')).toBeInTheDocument()
    expect(fake.requests.find((request) => request.path === '/api/games')?.search.get('genre')).toBe('Survival')

    const filters = screen.getByRole('complementary', { name: 'Filters' })
    await user.click(within(filters).getByText('Puzzle'))

    await waitFor(() => {
      expect(location.current).toBe('/browse?genre=Puzzle')
    })
    expect(await screen.findByText('Tessera')).toBeInTheDocument()
    expect(screen.queryByText('Canopy')).not.toBeInTheDocument()
  })

  it('searches from the header, from any page', async () => {
    fakeStore()
    const { user, location } = renderApp('/')

    const box = await screen.findAllByRole('searchbox', { name: 'Search the store' })
    await user.type(box[0] as HTMLElement, 'orbit{Enter}')

    await waitFor(() => {
      expect(location.current).toBe('/browse?q=orbit')
    })
    expect(await screen.findByRole('heading', { level: 1, name: 'Results for “orbit”' })).toBeInTheDocument()
  })

  it('shows sold-out and never-stocked games as such, and offers no way to add them', async () => {
    fakeStore()
    renderApp('/browse')

    expect(await screen.findByText('Sold out')).toBeInTheDocument()
    expect(screen.getByText('Not in stock yet')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add Canopy to cart' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add Tessera to cart' })).toBeInTheDocument()
  })

  it('says so when nothing matches, and offers the way back', async () => {
    fakeStore()
    renderApp('/browse?q=zzzz')

    expect(await screen.findByText('No games match')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Clear the filters' })).toHaveAttribute('href', '/browse')
  })
})

describe('a game’s page', () => {
  it('shows the game, and adding it fills the cart', async () => {
    fakeStore()
    const { user } = renderApp('/games/sunless-orbit')

    expect(await screen.findByRole('heading', { level: 1, name: 'Sunless Orbit' })).toBeInTheDocument()
    expect(screen.getByText('In stock')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'More: copies of Sunless Orbit' }))
    await user.click(screen.getByRole('button', { name: 'Add to cart' }))

    expect(await screen.findByText('Added to your cart')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cart, 2 items' })).toBeInTheDocument()
    expect(screen.getByText('2 in your cart')).toBeInTheDocument()
  })

  it('offers no more copies than are left', async () => {
    fakeStore()
    const { user } = renderApp('/games/ninefold')

    expect(await screen.findByText('Only 2 left')).toBeInTheDocument()
    const more = screen.getByRole('button', { name: 'More: copies of Ninefold' })
    await user.click(more)
    expect(more).toBeDisabled()
  })

  it('cannot be bought when it is sold out', async () => {
    fakeStore()
    renderApp('/games/canopy')

    const button = await screen.findByRole('button', { name: 'Sold out' })
    expect(button).toBeDisabled()
  })

  it('is a helpful dead end for a game the store does not sell', async () => {
    fakeStore()
    renderApp('/games/no-such-game')

    expect(await screen.findByText("We couldn't find that game")).toBeInTheDocument()
  })

  it('goes straight to checkout with "Buy now"', async () => {
    fakeStore()
    const { user, location } = renderApp('/games/tessera')

    await user.click(await screen.findByRole('button', { name: 'Buy now' }))

    await waitFor(() => {
      expect(location.current).toBe('/checkout')
    })
  })
})

import { screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeStore } from './fakeStore'
import { cartLine, renderApp } from './render'

describe('the frame around every page', () => {
  it('turns a failed sign-in into a message, and tidies the URL', async () => {
    fakeStore()
    const { location } = renderApp('/?signin=failed')

    expect(await screen.findByRole('alert')).toHaveTextContent("Sign-in didn't complete")
    await waitFor(() => {
      expect(location.current).toBe('/')
    })
  })

  it('opens the cart from the header, with what is in it', async () => {
    fakeStore()
    const { user } = renderApp('/', { cart: [cartLine({ quantity: 2 })] })

    await user.click(screen.getByRole('button', { name: 'Cart, 2 items' }))

    const drawer = await screen.findByRole('dialog', { name: 'Your cart (2 items)' })
    expect(drawer).toHaveTextContent('Sunless Orbit')
    expect(drawer).toHaveTextContent('$89.98')
    expect(drawer).toHaveTextContent('You save$30.00')
  })

  it('has a way to skip straight to the content', () => {
    fakeStore()
    renderApp('/')

    expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveAttribute('href', '#main')
  })

  it('answers an unknown address with a page, not a blank screen', async () => {
    fakeStore()
    renderApp('/definitely/not/a/page')

    expect(await screen.findByText("There's nothing on this page")).toBeInTheDocument()
  })
})

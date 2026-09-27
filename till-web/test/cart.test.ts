import { describe, expect, it } from 'vitest'
import { makeStore } from '../src/app/store'
import {
  MAX_LINES,
  MAX_QUANTITY,
  added,
  cartSlice,
  cleared,
  quantitySet,
  removed,
  replaced,
  selectCartCount,
  selectCartSavings,
  selectCartSubtotal,
} from '../src/features/cart/cartSlice'
import { CART_STORAGE_KEY, loadCart, saveCart } from '../src/features/cart/persistence'
import { game } from './fixtures'
import { cartLine } from './render'

const reduce = cartSlice.reducer

describe('the cart', () => {
  it('adds a game, and adding it again adds copies rather than a second line', () => {
    const orbit = game({ sku: 'sunless-orbit' })
    let state = reduce(undefined, added({ game: orbit }))
    state = reduce(state, added({ game: orbit, quantity: 2 }))
    expect(state.lines).toHaveLength(1)
    expect(state.lines[0]?.quantity).toBe(3)
  })

  it('never holds more copies of a game than one order may', () => {
    const state = reduce(undefined, added({ game: game(), quantity: 25 }))
    expect(state.lines[0]?.quantity).toBe(MAX_QUANTITY)
    expect(reduce(state, quantitySet({ sku: state.lines[0]?.sku ?? '', quantity: 0 })).lines[0]?.quantity).toBe(1)
  })

  it('stops at the most games one order may hold', () => {
    let state = reduce(undefined, { type: 'noop' })
    for (let i = 0; i < MAX_LINES + 3; i++) {
      state = reduce(state, added({ game: game() }))
    }
    expect(state.lines).toHaveLength(MAX_LINES)
  })

  it('removes and clears', () => {
    const state = reduce(reduce(undefined, added({ game: game({ sku: 'a' }) })), added({ game: game({ sku: 'b' }) }))
    expect(reduce(state, removed('a')).lines.map((line) => line.sku)).toEqual(['b'])
    expect(reduce(state, cleared()).lines).toEqual([])
  })

  it('counts copies, totals prices and adds up what the discounts save', () => {
    const store = makeStore({ cart: { lines: [] } })
    store.dispatch(added({ game: game({ priceCents: 4499, listPriceCents: 5999 }), quantity: 2 }))
    store.dispatch(added({ game: game({ priceCents: 1000, listPriceCents: 1000 }) }))
    const state = store.getState()
    expect(selectCartCount(state)).toBe(3)
    expect(selectCartSubtotal(state)).toBe(2 * 4499 + 1000)
    expect(selectCartSavings(state)).toBe(2 * 1500)
  })

  it('takes a whole cart from another tab, within the limits', () => {
    const state = reduce(undefined, replaced([cartLine({ quantity: 99 })]))
    expect(state.lines[0]?.quantity).toBe(MAX_QUANTITY)
  })
})

describe('keeping the cart between visits', () => {
  it('writes the cart to local storage as it changes, and a new store starts from it', () => {
    const store = makeStore({ cart: { lines: [] } })
    store.dispatch(added({ game: game({ sku: 'tessera', title: 'Tessera' }) }))

    expect(loadCart()).toHaveLength(1)
    expect(makeStore().getState().cart.lines[0]?.title).toBe('Tessera')

    store.dispatch(cleared())
    expect(localStorage.getItem(CART_STORAGE_KEY)).toBeNull()
  })

  it('drops whatever in storage is not a well-formed line, rather than trusting it', () => {
    localStorage.setItem(
      CART_STORAGE_KEY,
      JSON.stringify([cartLine(), { sku: 'x' }, cartLine({ sku: 'neg', priceCents: -1 }), cartLine({ sku: 'frac', quantity: 1.5 }), null]),
    )
    expect(loadCart().map((line) => line.sku)).toEqual(['sunless-orbit'])

    localStorage.setItem(CART_STORAGE_KEY, '{not json')
    expect(loadCart()).toEqual([])
    localStorage.setItem(CART_STORAGE_KEY, '{"lines":[]}')
    expect(loadCart()).toEqual([])
  })

  it('survives storage that refuses to be written', () => {
    const full = {
      setItem: () => {
        throw new Error('QuotaExceededError')
      },
      removeItem: () => undefined,
    } as unknown as Storage
    expect(() => {
      saveCart([cartLine()], full)
    }).not.toThrow()
  })
})

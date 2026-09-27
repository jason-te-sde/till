import { act, renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fingerprint, useCheckoutAttempt } from '../src/features/checkout/useCheckoutAttempt'
import { cartLine } from './render'

describe('the cart fingerprint', () => {
  it('is the same for the same games in any order, and different for any other quantity', () => {
    const a = cartLine({ sku: 'a' })
    const b = cartLine({ sku: 'b', quantity: 2 })
    expect(fingerprint([a, b])).toBe(fingerprint([b, a]))
    expect(fingerprint([a, b])).not.toBe(fingerprint([a, { ...b, quantity: 3 }]))
    expect(fingerprint([a])).toMatch(/^[0-9a-f]{8}$/)
  })
})

describe('a checkout attempt', () => {
  it('sends the same key for a retry of the same cart, and a new one when the cart changes', () => {
    const cart = [cartLine()]
    const { result, rerender } = renderHook(({ lines }) => useCheckoutAttempt(lines), { initialProps: { lines: cart } })
    const first = result.current.key

    rerender({ lines: [cartLine()] })
    expect(result.current.key).toBe(first)

    rerender({ lines: [cartLine({ quantity: 2 })] })
    expect(result.current.key).not.toBe(first)
  })

  it('starts a new attempt once it is finished, so buying the same cart again is a new purchase', () => {
    const cart = [cartLine()]
    const { result } = renderHook(() => useCheckoutAttempt(cart))
    const first = result.current.key

    act(() => {
      result.current.placed('order-1')
    })
    expect(result.current.orderId).toBe('order-1')

    act(() => {
      result.current.finished()
    })
    expect(result.current.orderId).toBeUndefined()
    expect(result.current.key).not.toBe(first)
  })

  it('survives a reload: the order it placed is still the one it pays for', () => {
    const cart = [cartLine()]
    const before = renderHook(() => useCheckoutAttempt(cart))
    act(() => {
      before.result.current.placed('order-7')
    })
    const key = before.result.current.key
    before.unmount()

    const after = renderHook(() => useCheckoutAttempt(cart))
    expect(after.result.current.orderId).toBe('order-7')
    expect(after.result.current.key).toBe(key)
  })

  it('keeps its transitions stable, so an effect depending on them runs once', () => {
    const cart = [cartLine()]
    const { result, rerender } = renderHook(() => useCheckoutAttempt(cart))
    const { placed, finished } = result.current
    rerender()
    expect(result.current.placed).toBe(placed)
    expect(result.current.finished).toBe(finished)
  })
})

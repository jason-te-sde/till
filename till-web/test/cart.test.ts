import { describe, expect, it } from 'vitest'
import { add, count, EMPTY, remove, toLines, total } from '../src/shop/cart'

describe('the basket', () => {
  it('will not add more than the shop believes is available', () => {
    let cart = EMPTY
    for (let i = 0; i < 5; i++) {
      cart = add(cart, 'widget', 2)
    }

    // A courtesy, not a guarantee: the number it checks was read some time ago. The reservation is
    // what decides, which is why the checkout screen still has to handle a refusal.
    expect(cart.get('widget')).toBe(2)
  })

  it('returns the same basket when nothing can be added, so React does not re-render', () => {
    const full = add(EMPTY, 'widget', 1)

    expect(add(full, 'widget', 1)).toBe(full)
  })

  it('drops a line rather than leaving a zero in it', () => {
    const one = add(EMPTY, 'widget', 5)

    expect(remove(one, 'widget').has('widget')).toBe(false)
  })

  it('counts items and money across lines', () => {
    let cart = add(EMPTY, 'widget', 9)
    cart = add(cart, 'widget', 9)
    cart = add(cart, 'grommet', 9)

    expect(count(cart)).toBe(3)
    // Two widgets at 1299 and a grommet at 249.
    expect(total(cart)).toBe(1299 * 2 + 249)
  })

  it('sorts lines by SKU, so two equal baskets make the same request', () => {
    const one = add(add(EMPTY, 'widget', 9), 'grommet', 9)
    const other = add(add(EMPTY, 'grommet', 9), 'widget', 9)

    // The service canonicalises anyway; sending them in a stable order is what makes the
    // idempotency fingerprint of a retry match.
    expect(toLines(one)).toEqual(toLines(other))
    expect(toLines(one).map((line) => line.sku)).toEqual(['grommet', 'widget'])
  })

  it('ignores an unknown SKU when totalling rather than counting it as free', () => {
    const cart = add(EMPTY, 'not-in-the-catalogue', 5)

    expect(count(cart)).toBe(1)
    expect(total(cart)).toBe(0)
  })
})

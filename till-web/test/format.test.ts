import { describe, expect, it } from 'vitest'
import { compact, money, remaining } from '../src/format'

describe('formatting', () => {
  it('groups thousands and compacts past five figures', () => {
    expect(compact(0)).toBe('0')
    expect(compact(1284)).toBe('1,284')
    expect(compact(9999)).toBe('9,999')
    expect(compact(12_900)).toBe('12.9K')
    expect(compact(-4200)).toBe('-4,200')
  })

  it('rounds a countdown up, so a live hold never reads as zero', () => {
    // 400ms left with a working Pay button showing 0:00 is a page a customer thinks is broken.
    expect(remaining(400)).toBe('0:01')
    expect(remaining(1000)).toBe('0:01')
    expect(remaining(59_400)).toBe('1:00')
    expect(remaining(120_000)).toBe('2:00')
  })

  it('shows zero only when the time really has gone', () => {
    expect(remaining(0)).toBe('0:00')
    expect(remaining(-5000)).toBe('0:00')
  })

  it('pads seconds', () => {
    expect(remaining(65_000)).toBe('1:05')
  })

  it('formats money from pence', () => {
    expect(money(1299)).toBe('£12.99')
    expect(money(0)).toBe('£0.00')
  })
})

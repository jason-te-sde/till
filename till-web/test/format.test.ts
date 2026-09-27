import { describe, expect, it } from 'vitest'
import { ago, compact, money, plural, releaseDate, remaining } from '../src/format'

describe('money', () => {
  it('formats cents as dollars with two places', () => {
    expect(money(4499)).toBe('$44.99')
    expect(money(4490)).toBe('$44.90')
    expect(money(0)).toBe('$0.00')
    expect(money(123456)).toBe('$1,234.56')
  })
})

describe('releaseDate', () => {
  it('shows the calendar day it names, in every timezone', () => {
    // Parsed as a local date this would be the ninth anywhere west of Greenwich.
    expect(releaseDate('2026-09-10')).toBe('Sep 10, 2026')
    expect(releaseDate('2026-01-01')).toBe('Jan 1, 2026')
  })
})

describe('remaining', () => {
  it('counts down as m:ss, rounding up so zero means zero', () => {
    expect(remaining(15 * 60_000)).toBe('15:00')
    expect(remaining(61_000)).toBe('1:01')
    expect(remaining(400)).toBe('0:01')
    expect(remaining(0)).toBe('0:00')
    expect(remaining(-5000)).toBe('0:00')
  })
})

describe('ago', () => {
  const now = Date.parse('2026-09-18T12:00:00Z')
  it('says how long ago, in the unit a person would use', () => {
    expect(ago('2026-09-18T11:59:40Z', now)).toBe('just now')
    expect(ago('2026-09-18T11:57:00Z', now)).toBe('3 minutes ago')
    expect(ago('2026-09-18T09:00:00Z', now)).toBe('3 hours ago')
    expect(ago('2026-09-17T08:00:00Z', now)).toBe('yesterday')
  })
})

describe('compact and plural', () => {
  it('keeps small numbers exact and shortens large ones', () => {
    expect(compact(1284)).toBe('1,284')
    expect(compact(12_900)).toBe('12.9K')
  })

  it('says one game, three games, and irregular plurals when told', () => {
    expect(plural(1, 'game')).toBe('1 game')
    expect(plural(3, 'game')).toBe('3 games')
    expect(plural(2, 'copy', 'copies')).toBe('2 copies')
  })
})

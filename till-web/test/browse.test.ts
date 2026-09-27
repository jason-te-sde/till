import { describe, expect, it } from 'vitest'
import { PAGE_SIZE, headingFor, pageWindow, readBrowse, toSearch, writeBrowse } from '../src/features/catalogue/browse'

describe('the browse URL', () => {
  it('round-trips every filter through the query string', () => {
    const url = new URLSearchParams('q=rust&genre=Puzzle&tag=co-op&maxPrice=1500&onSale=true&sort=price-asc&page=3')
    const browse = readBrowse(url)
    expect(browse).toEqual({ q: 'rust', genre: 'Puzzle', tag: 'co-op', maxPrice: 1500, onSale: true, sort: 'price-asc', page: 3 })
    expect(writeBrowse(browse).toString()).toBe(url.toString())
  })

  it('leaves defaults out, so links stay short', () => {
    expect(writeBrowse(readBrowse(new URLSearchParams())).toString()).toBe('')
  })

  it('reads a hand-mangled link as a sensible page rather than failing', () => {
    const browse = readBrowse(new URLSearchParams('sort=cheapest&page=-4&maxPrice=abc&onSale=yes'))
    expect(browse).toMatchObject({ sort: '', page: 1, maxPrice: undefined, onSale: false })
  })
})

describe('the API request', () => {
  it('turns one-based pages into zero-based ones and asks for a full page', () => {
    expect(toSearch(readBrowse(new URLSearchParams('page=2')))).toMatchObject({ page: 1, size: PAGE_SIZE })
  })

  it('never asks the server to rank by relevance with nothing to be relevant to', () => {
    expect(toSearch(readBrowse(new URLSearchParams('sort=relevance'))).sort).toBe('')
    expect(toSearch(readBrowse(new URLSearchParams('sort=relevance&q=orbit'))).sort).toBe('relevance')
  })
})

describe('headings and page numbers', () => {
  it('names the page after what it shows', () => {
    expect(headingFor(readBrowse(new URLSearchParams('q=orbit')))).toBe('Results for “orbit”')
    expect(headingFor(readBrowse(new URLSearchParams('genre=Puzzle')))).toBe('Puzzle')
    expect(headingFor(readBrowse(new URLSearchParams('onSale=true')))).toBe('On sale')
    expect(headingFor(readBrowse(new URLSearchParams()))).toBe('All games')
  })

  it('shows every page when there are few, and gaps around the current one when there are many', () => {
    expect(pageWindow(2, 5)).toEqual([1, 2, 3, 4, 5])
    expect(pageWindow(6, 20)).toEqual([1, null, 5, 6, 7, null, 20])
    expect(pageWindow(1, 20)).toEqual([1, 2, null, 20])
  })
})

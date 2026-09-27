import type { GameSearch } from '../../api/types'

/** Games per page: four rows of a four-wide grid, three of a desktop's six-wide shelf. */
export const PAGE_SIZE = 24

export type Sort = '' | 'relevance' | 'featured' | 'bestselling' | 'newest' | 'price-asc' | 'price-desc' | 'title'

/**
 * A browse page's state, as the URL holds it.
 *
 * The URL is the state, not a copy of it. A filtered, sorted, third page of results is a link that can
 * be shared, bookmarked, refreshed and reached again with the back button — none of which works if the
 * filters live in component state and the URL merely follows along.
 */
export interface Browse {
  readonly q: string
  readonly genre: string
  readonly tag: string
  /** An upper bound on price, in cents; undefined for any price. */
  readonly maxPrice: number | undefined
  readonly onSale: boolean
  readonly sort: Sort
  /** One-based, as a person counts pages; the API's are zero-based. */
  readonly page: number
}

export const SORTS: readonly { value: Sort; label: string; needsText?: boolean }[] = [
  { value: 'relevance', label: 'Best match', needsText: true },
  { value: 'featured', label: 'Featured' },
  { value: 'bestselling', label: 'Best selling' },
  { value: 'newest', label: 'Newest' },
  { value: 'price-asc', label: 'Price: low to high' },
  { value: 'price-desc', label: 'Price: high to low' },
  { value: 'title', label: 'Name: A to Z' },
]

export const PRICE_CAPS: readonly { cents: number; label: string }[] = [
  { cents: 1000, label: 'Under $10' },
  { cents: 1500, label: 'Under $15' },
  { cents: 2000, label: 'Under $20' },
  { cents: 3000, label: 'Under $30' },
]

const SORT_VALUES = new Set<string>(SORTS.map((sort) => sort.value))

/**
 * Reads a browse page's state out of its URL, ignoring anything malformed rather than failing on it —
 * a hand-edited link should land on a sensible page, not an error.
 *
 * @param params the URL's query
 * @returns the state
 */
export function readBrowse(params: URLSearchParams): Browse {
  const maxPrice = Number(params.get('maxPrice'))
  const page = Number(params.get('page'))
  const sort = params.get('sort') ?? ''
  return {
    q: (params.get('q') ?? '').slice(0, 100),
    genre: params.get('genre') ?? '',
    tag: params.get('tag') ?? '',
    maxPrice: Number.isInteger(maxPrice) && maxPrice > 0 ? maxPrice : undefined,
    onSale: params.get('onSale') === 'true',
    sort: SORT_VALUES.has(sort) ? (sort as Sort) : '',
    page: Number.isInteger(page) && page > 1 ? page : 1,
  }
}

/**
 * The URL for a browse state, leaving out everything at its default so links stay short.
 *
 * @param browse the state
 * @returns the query
 */
export function writeBrowse(browse: Browse): URLSearchParams {
  const params = new URLSearchParams()
  if (browse.q.trim() !== '') params.set('q', browse.q.trim())
  if (browse.genre !== '') params.set('genre', browse.genre)
  if (browse.tag !== '') params.set('tag', browse.tag)
  if (browse.maxPrice !== undefined) params.set('maxPrice', String(browse.maxPrice))
  if (browse.onSale) params.set('onSale', 'true')
  if (browse.sort !== '') params.set('sort', browse.sort)
  if (browse.page > 1) params.set('page', String(browse.page))
  return params
}

/**
 * The API request for a browse state.
 *
 * @param browse the state
 * @returns the search
 */
export function toSearch(browse: Browse): GameSearch {
  return {
    q: browse.q.trim(),
    genre: browse.genre,
    tag: browse.tag,
    ...(browse.maxPrice === undefined ? {} : { maxPriceCents: browse.maxPrice }),
    onSale: browse.onSale,
    // "Best match" means nothing without text; the server would refuse to rank by nothing, so the
    // page asks for its default instead.
    sort: browse.sort === 'relevance' && browse.q.trim() === '' ? '' : browse.sort,
    page: browse.page - 1,
    size: PAGE_SIZE,
  }
}

/**
 * @param browse the state
 * @returns the page's heading
 */
export function headingFor(browse: Browse): string {
  if (browse.q.trim() !== '') return `Results for “${browse.q.trim()}”`
  if (browse.genre !== '') return browse.genre
  if (browse.onSale) return 'On sale'
  if (browse.tag !== '') return `Tagged “${browse.tag}”`
  if (browse.sort === 'newest') return 'New releases'
  if (browse.sort === 'bestselling') return 'Best sellers'
  return 'All games'
}

/**
 * Page numbers to show around the current one, with gaps as nulls: 1 … 4 5 [6] 7 8 … 20.
 *
 * @param current the page being shown
 * @param total how many pages there are
 * @returns the pages, and null where a gap goes
 */
export function pageWindow(current: number, total: number): (number | null)[] {
  if (total <= 7) {
    return Array.from({ length: total }, (_, i) => i + 1)
  }
  const pages = new Set([1, total, current - 1, current, current + 1].filter((page) => page >= 1 && page <= total))
  const sorted = [...pages].sort((a, b) => a - b)
  const result: (number | null)[] = []
  let previous = 0
  for (const page of sorted) {
    if (page - previous > 1) {
      result.push(null)
    }
    result.push(page)
    previous = page
  }
  return result
}

import { money } from '../format'

/**
 * A price, with its discount when it has one.
 *
 * The discount is a number in a badge and the list price struck through beside it — never colour
 * alone, so it reads the same in greyscale, in a screenshot and to a screen reader, which is told the
 * whole thing in one sentence rather than three disconnected numbers.
 */
export function Price({
  priceCents,
  listPriceCents,
  currency = 'USD',
  size = 'md',
  inverse = false,
}: {
  priceCents: number
  listPriceCents: number
  currency?: string
  size?: 'sm' | 'md' | 'lg'
  /** Light text, for a price drawn over cover art rather than on the page's own surface. */
  inverse?: boolean
}) {
  const discounted = listPriceCents > priceCents
  const percent = discounted ? Math.round((1 - priceCents / listPriceCents) * 100) : 0
  const spoken = discounted
    ? `${money(priceCents, currency)}, ${String(percent)}% off ${money(listPriceCents, currency)}`
    : money(priceCents, currency)
  const text = { sm: 'text-sm', md: 'text-base', lg: 'text-3xl' }[size]
  const badge = { sm: 'px-1.5 py-0.5 text-xs', md: 'px-2 py-0.5 text-sm', lg: 'px-2.5 py-1 text-lg' }[size]
  return (
    <span className="inline-flex items-center gap-2">
      <span className="sr-only">{spoken}</span>
      {discounted && (
        <span aria-hidden="true" className={`rounded-md bg-sale font-bold text-sale-ink ${badge}`}>
          -{percent}%
        </span>
      )}
      <span aria-hidden="true" className="inline-flex items-baseline gap-1.5">
        {discounted && (
          <span className={`line-through ${inverse ? 'text-white/60' : 'text-ink-muted'} ${size === 'lg' ? 'text-lg' : 'text-xs'}`}>
            {money(listPriceCents, currency)}
          </span>
        )}
        <span className={`font-semibold ${inverse ? 'text-white' : 'text-ink'} ${text}`}>{money(priceCents, currency)}</span>
      </span>
    </span>
  )
}

/**
 * How numbers, prices and times read on the page.
 *
 * Its own module because a file that exports both a component and a helper cannot be hot-reloaded —
 * the fast-refresh boundary is the file — and because formatting is the part of a storefront most
 * worth unit-testing on its own: a price that renders as `$44.9` is a bug nobody's type checker sees.
 */

const moneyFormats = new Map<string, Intl.NumberFormat>()

/**
 * @param cents an amount in minor units, as the store sends every price
 * @param currency ISO 4217; the store trades in USD
 * @returns `$44.99`
 */
export function money(cents: number, currency = 'USD'): string {
  let format = moneyFormats.get(currency)
  if (format === undefined) {
    format = new Intl.NumberFormat('en-US', { style: 'currency', currency })
    moneyFormats.set(currency, format)
  }
  return format.format(cents / 100)
}

const releaseFormat = new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeZone: 'UTC' })

/**
 * A release date, which is a calendar day rather than an instant.
 *
 * Formatted in UTC because `2026-09-10` parsed as a date is midnight UTC, and shown in a timezone west
 * of Greenwich that midnight is still the ninth — every game would come out a day early in America.
 *
 * @param isoDate `2026-09-10`
 * @returns `Sep 10, 2026`
 */
export function releaseDate(isoDate: string): string {
  return releaseFormat.format(new Date(`${isoDate}T00:00:00Z`))
}

const instantFormat = new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' })

/**
 * @param iso an instant from the server
 * @returns `Sep 18, 2026, 12:15 PM`, in the viewer's own timezone
 */
export function dateTime(iso: string): string {
  return instantFormat.format(new Date(iso))
}

const relative = new Intl.RelativeTimeFormat('en-US', { numeric: 'auto' })

/**
 * @param iso an instant from the server
 * @param now the current instant, in milliseconds
 * @returns `3 minutes ago`, `just now`, `yesterday`
 */
export function ago(iso: string, now: number = Date.now()): string {
  const seconds = Math.round((new Date(iso).getTime() - now) / 1000)
  const magnitude = Math.abs(seconds)
  if (magnitude < 45) {
    return 'just now'
  }
  if (magnitude < 45 * 60) {
    return relative.format(Math.round(seconds / 60), 'minute')
  }
  if (magnitude < 22 * 3600) {
    return relative.format(Math.round(seconds / 3600), 'hour')
  }
  return relative.format(Math.round(seconds / 86_400), 'day')
}

/**
 * A countdown, as m:ss.
 *
 * Rounds up, so a hold with 400 milliseconds left reads "0:01" rather than "0:00" — a customer looking
 * at zero while the button still works is a customer who thinks the page is broken.
 *
 * @param remainingMs what is left
 * @returns `14:59`
 */
export function remaining(remainingMs: number): string {
  if (remainingMs <= 0) {
    return '0:00'
  }
  const seconds = Math.ceil(remainingMs / 1000)
  return `${String(Math.floor(seconds / 60))}:${String(seconds % 60).padStart(2, '0')}`
}

/**
 * @param value a count
 * @returns 1,284 rather than 1284; 12.9K past five figures, where the exact digit stops being the point
 */
export function compact(value: number): string {
  if (Math.abs(value) < 10_000) {
    return value.toLocaleString('en-US')
  }
  return new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(value)
}

/**
 * @param count how many
 * @param singular the word for one
 * @param plural the word for several, when adding an "s" is wrong
 * @returns `1 game`, `3 games`
 */
export function plural(count: number, singular: string, plural = `${singular}s`): string {
  return `${count.toLocaleString('en-US')} ${count === 1 ? singular : plural}`
}

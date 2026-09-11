/**
 * Number and duration formatting.
 *
 * Its own module because a file that exports both a component and a helper cannot be hot-reloaded —
 * the fast-refresh boundary is the file — and because these are the two things in the console worth
 * unit-testing on their own.
 */

/** 1,284 rather than 1284; 12.9K past five figures, where the exact digit stops being the point. */
export function compact(value: number): string {
  if (Math.abs(value) < 10_000) {
    return value.toLocaleString('en-US')
  }
  return new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(
    value,
  )
}

/**
 * A countdown, as m:ss.
 *
 * Rounds up, so a hold with 400 milliseconds left reads "0:01" rather than "0:00" — a customer
 * looking at zero while the button still works is a customer who thinks the page is broken.
 */
export function remaining(remainingMs: number): string {
  if (remainingMs <= 0) {
    return '0:00'
  }
  const seconds = Math.ceil(remainingMs / 1000)
  return `${String(Math.floor(seconds / 60))}:${String(seconds % 60).padStart(2, '0')}`
}

export function money(pence: number): string {
  return new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP' }).format(pence / 100)
}

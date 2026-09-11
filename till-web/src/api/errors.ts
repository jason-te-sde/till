/** How far short one SKU fell, from an RFC 9457 problem body. */
export interface Shortfall {
  readonly sku: string
  readonly requested: number
  readonly available: number
}

/**
 * The service answered, and the answer was no.
 *
 * Carries `code` rather than only the status, because the interesting distinctions live inside one
 * status: a 409 is "somebody got there first" or "you already paid for this", and a page showing the
 * same sentence for both is a page nobody can act on.
 *
 * Fields are declared and assigned rather than written as constructor parameter properties, because
 * the build sets `erasableSyntaxOnly` — every type annotation here can be stripped without a
 * transform, which is what lets the same source run under a runtime that only erases types.
 */
export class TillError extends Error {
  readonly status: number
  readonly code: string | undefined
  readonly detail: string
  readonly shortfalls: readonly Shortfall[]

  constructor(
    status: number,
    code: string | undefined,
    detail: string,
    shortfalls: readonly Shortfall[] = [],
  ) {
    super(`${String(status)} ${code ?? ''}: ${detail}`.trim())
    this.name = 'TillError'
    this.status = status
    this.code = code
    this.detail = detail
    this.shortfalls = shortfalls
  }

  /** Whether this is worth offering the customer a smaller basket for. */
  get isOutOfStock(): boolean {
    return this.code === 'INSUFFICIENT_STOCK'
  }

  /** Whether the hold ran out while they were deciding. */
  get isExpired(): boolean {
    return this.code === 'RESERVATION_EXPIRED'
  }
}

/** The request never got an answer, so nobody knows whether it happened. */
export class TillUnreachableError extends Error {
  constructor(cause: unknown) {
    super('the till service could not be reached', { cause })
    this.name = 'TillUnreachableError'
  }
}

/** Reads a problem body without trusting it to be one. */
export function problemFrom(status: number, body: unknown, fallback: string): TillError {
  if (typeof body !== 'object' || body === null) {
    return new TillError(status, undefined, fallback)
  }
  const problem = body as Record<string, unknown>
  const code = typeof problem['code'] === 'string' ? problem['code'] : undefined
  const detail = typeof problem['detail'] === 'string' ? problem['detail'] : fallback
  const shortfalls = Array.isArray(problem['shortfalls'])
    ? (problem['shortfalls'] as unknown[]).filter(isShortfall)
    : []
  return new TillError(status, code, detail, shortfalls)
}

function isShortfall(value: unknown): value is Shortfall {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const candidate = value as Record<string, unknown>
  return (
    typeof candidate['sku'] === 'string' &&
    typeof candidate['requested'] === 'number' &&
    typeof candidate['available'] === 'number'
  )
}

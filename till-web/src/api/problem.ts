import type { SerializedError } from '@reduxjs/toolkit'
import type { FetchBaseQueryError } from '@reduxjs/toolkit/query'
import type { Problem } from './types'

/**
 * Every failure as a {@link Problem}, whichever layer it came from.
 *
 * The store answers every error with an RFC 9457 problem carrying a `code`, and the contract says so.
 * What reaches a component can still be something else — the network was down, the proxy answered
 * with an HTML page, the request timed out — and a page that has to tell those apart before it can
 * say anything will, sooner or later, say nothing. So they are all turned into the one shape here, with
 * codes of their own, and a page only ever branches on `code`.
 *
 * @param error what RTK Query reported, if anything
 * @returns the problem, or undefined when there was no error
 */
export function problemOf(error: FetchBaseQueryError | SerializedError | undefined): Problem | undefined {
  if (error === undefined) {
    return undefined
  }
  if (!('status' in error)) {
    // A thrown exception inside the query machinery rather than an HTTP answer.
    return synthetic(0, 'CLIENT_ERROR', 'Something went wrong', error.message ?? 'the page failed to make the request')
  }
  const { status } = error
  if (typeof status === 'number') {
    return isProblem(error.data)
      ? error.data
      : synthetic(status, `HTTP_${String(status)}`, 'Unexpected response', `the store answered ${String(status)}`)
  }
  switch (status) {
    case 'FETCH_ERROR':
      return synthetic(0, 'NETWORK', 'Offline', 'the store could not be reached')
    case 'TIMEOUT_ERROR':
      return synthetic(0, 'TIMEOUT', 'Timed out', 'the store took too long to answer')
    case 'PARSING_ERROR':
      // Almost always a proxy's own error page, served as HTML where JSON was expected.
      return synthetic(error.originalStatus, `HTTP_${String(error.originalStatus)}`, 'Unexpected response',
        `the store answered ${String(error.originalStatus)} with something that is not JSON`)
    case 'CUSTOM_ERROR':
      return synthetic(0, 'CLIENT_ERROR', 'Something went wrong', error.error)
  }
}

/**
 * What to tell a customer, by code.
 *
 * Written for the person looking at the page, not for the developer: the server's `detail` is precise
 * but it is written for logs. Codes this does not know fall back to it, which is always accurate and
 * occasionally terse.
 *
 * @param problem the failure
 * @returns a sentence to show
 */
export function messageFor(problem: Problem): string {
  switch (problem.code) {
    case 'NETWORK':
      return "We can't reach the store right now. Check your connection and try again."
    case 'TIMEOUT':
      return 'The store is taking too long to answer. Please try again.'
    case 'LEDGER_UNAVAILABLE':
      return 'Our stock system is briefly unavailable. Nothing was charged or held twice — it is safe to try again.'
    case 'STORE_UNAVAILABLE':
      return 'The store is briefly unavailable — it may be restarting. Please try again in a moment.'
    case 'RATE_LIMITED':
      return 'That was a lot of requests in a short time. Wait a moment, then try again.'
    case 'INSUFFICIENT_STOCK':
      return "Some of the games in your cart don't have enough copies left."
    case 'UNKNOWN_SKU':
      return "One of these games isn't in stock yet."
    case 'ORDER_EXPIRED':
      return 'The hold on this order ran out before it was paid for, so the copies went back on sale.'
    case 'ORDER_CANCELLED':
      return 'This order was cancelled.'
    case 'ORDER_PAID':
      return 'This order is already paid for.'
    case 'IDEMPOTENCY_KEY_REUSED':
      return 'Your cart changed during checkout. Please review it and place the order again.'
    case 'UNAUTHORIZED':
      return 'Please sign in to continue.'
    case 'FORBIDDEN':
      return "Your account can't do that."
    case 'CSRF':
      return 'Your session changed — perhaps in another tab. Reload the page and try again.'
    case 'NOT_FOUND':
      return "We couldn't find that."
    case 'INTERNAL_ERROR':
      return 'Something went wrong on our side, and it has been logged. Please try again in a moment.'
    default:
      return capitalise(problem.detail)
  }
}

/**
 * @param problem the failure
 * @returns whether trying the same thing again could work — the network, the ledger, a timeout
 */
export function isTransient(problem: Problem): boolean {
  return (
    ['NETWORK', 'TIMEOUT', 'LEDGER_UNAVAILABLE', 'STORE_UNAVAILABLE', 'RATE_LIMITED', 'INTERNAL_ERROR'].includes(problem.code) ||
    problem.status >= 502
  )
}

function isProblem(data: unknown): data is Problem {
  return (
    typeof data === 'object' &&
    data !== null &&
    typeof (data as { code?: unknown }).code === 'string' &&
    typeof (data as { status?: unknown }).status === 'number'
  )
}

function synthetic(status: number, code: string, title: string, detail: string): Problem {
  return { status, code, title, detail }
}

function capitalise(sentence: string): string {
  const trimmed = sentence.trim()
  if (trimmed === '') {
    return 'Something went wrong.'
  }
  const first = trimmed.charAt(0).toUpperCase() + trimmed.slice(1)
  return /[.!?]$/.test(first) ? first : `${first}.`
}

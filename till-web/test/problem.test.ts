import { describe, expect, it } from 'vitest'
import { csrfToken } from '../src/api/csrf'
import { isTransient, messageFor, problemOf } from '../src/api/problem'

describe('csrfToken', () => {
  it('reads the token the store set, and nothing else', () => {
    expect(csrfToken('SESSION=abc; XSRF-TOKEN=t0k3n; theme=dark')).toBe('t0k3n')
    expect(csrfToken('XSRF-TOKEN=a%3Db')).toBe('a=b')
    expect(csrfToken('SESSION=abc')).toBeUndefined()
    expect(csrfToken('')).toBeUndefined()
  })
})

describe('problemOf', () => {
  it('passes a problem from the store through untouched', () => {
    const body = { title: 'Not enough stock', status: 409, detail: 'short', code: 'INSUFFICIENT_STOCK', shortfalls: [] }
    expect(problemOf({ status: 409, data: body })).toEqual(body)
  })

  it('names a network failure, a timeout and a proxy page as problems of their own', () => {
    expect(problemOf({ status: 'FETCH_ERROR', error: 'TypeError: failed' })?.code).toBe('NETWORK')
    expect(problemOf({ status: 'TIMEOUT_ERROR', error: 'timed out' })?.code).toBe('TIMEOUT')
    expect(problemOf({ status: 'PARSING_ERROR', originalStatus: 502, data: '<html>', error: 'SyntaxError' })).toMatchObject({
      code: 'HTTP_502',
      status: 502,
    })
  })

  it('does not trust a body just because it is JSON', () => {
    expect(problemOf({ status: 500, data: { message: 'boom' } })?.code).toBe('HTTP_500')
  })

  it('is undefined when nothing failed', () => {
    expect(problemOf(undefined)).toBeUndefined()
  })
})

describe('messageFor and isTransient', () => {
  it('speaks to the customer for the codes it knows, and falls back to the detail', () => {
    expect(messageFor({ status: 503, code: 'LEDGER_UNAVAILABLE', title: '', detail: '' })).toMatch(/safe to try again/)
    expect(messageFor({ status: 403, code: 'CSRF', title: '', detail: '' })).toMatch(/Reload the page/)
    expect(messageFor({ status: 400, code: 'BAD_REQUEST', title: '', detail: 'size must be between 1 and 48' })).toBe(
      'Size must be between 1 and 48.',
    )
  })

  it('offers a retry for outages and never for a refusal', () => {
    expect(isTransient({ status: 503, code: 'LEDGER_UNAVAILABLE', title: '', detail: '' })).toBe(true)
    expect(isTransient({ status: 0, code: 'NETWORK', title: '', detail: '' })).toBe(true)
    expect(isTransient({ status: 429, code: 'RATE_LIMITED', title: '', detail: '' })).toBe(true)
    expect(isTransient({ status: 409, code: 'INSUFFICIENT_STOCK', title: '', detail: '' })).toBe(false)
    expect(isTransient({ status: 401, code: 'UNAUTHORIZED', title: '', detail: '' })).toBe(false)
  })
})

import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ErrorBoundary } from '../src/components/ErrorBoundary'

function Throws(): never {
  throw new Error('a panel blew up')
}

describe('a panel that throws during render', () => {
  beforeEach(() => {
    // React logs the caught error itself, and the boundary logs the component stack. Both are
    // wanted in production and neither is wanted in the test output.
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows a message instead of blanking the window', () => {
    render(
      <ErrorBoundary>
        <Throws />
      </ErrorBoundary>,
    )

    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.getByText('a panel blew up')).toBeInTheDocument()
  })

  it('says the service is not necessarily the problem', () => {
    render(
      <ErrorBoundary>
        <Throws />
      </ErrorBoundary>,
    )

    // The failure mode this exists for is a healthy ledger and a white screen. Somebody reading it
    // at three in the morning should not start by paging the on-call for the service.
    expect(screen.getByText(/the ledger is not involved/i)).toBeInTheDocument()
  })

  it('stays out of the way when nothing throws', () => {
    render(
      <ErrorBoundary>
        <p>the actual page</p>
      </ErrorBoundary>,
    )

    expect(screen.getByText('the actual page')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

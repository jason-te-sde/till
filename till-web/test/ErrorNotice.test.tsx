import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { TillError, TillUnreachableError } from '../src/api/client'
import { ErrorNotice } from '../src/components/ErrorNotice'

describe('reporting a failure', () => {
  it('lists every shortfall, because that is the one error a customer can act on', () => {
    render(
      <ErrorNotice
        error={
          new TillError(409, 'INSUFFICIENT_STOCK', 'not enough stock', [
            { sku: 'widget', requested: 5, available: 2 },
            { sku: 'gadget', requested: 3, available: 0 },
          ])
        }
      />,
    )

    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.getByText('Not enough left')).toBeInTheDocument()
    expect(screen.getByText(/wanted 5, 2 left/)).toBeInTheDocument()
    expect(screen.getByText(/wanted 3, 0 left/)).toBeInTheDocument()
  })

  it('distinguishes the refusals a customer would otherwise see the same sentence for', () => {
    const { unmount } = render(
      <ErrorNotice error={new TillError(409, 'ALREADY_COMMITTED', 'already committed')} />,
    )
    expect(screen.getByText('Already paid for')).toBeInTheDocument()
    unmount()

    render(<ErrorNotice error={new TillError(409, 'ALREADY_RELEASED', 'already released')} />)
    expect(screen.getByText('Already cancelled')).toBeInTheDocument()
  })

  it('says plainly when nobody knows whether the request happened', () => {
    render(<ErrorNotice error={new TillUnreachableError(new Error('offline'))} />)

    expect(screen.getByText('The till service could not be reached')).toBeInTheDocument()
    expect(screen.getByText(/may or may not have been applied/)).toBeInTheDocument()
  })

  it('renders nothing when there is nothing wrong', () => {
    const { container } = render(<ErrorNotice error={undefined} />)

    expect(container).toBeEmptyDOMElement()
  })

  it('offers a retry only when the caller has something to retry with', async () => {
    const onRetry = vi.fn()
    render(<ErrorNotice error={new TillError(503, 'CONTENTION', 'busy')} onRetry={onRetry} />)

    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))

    expect(onRetry).toHaveBeenCalledOnce()
  })
})

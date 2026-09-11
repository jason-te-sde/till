import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { TokenGate } from '../src/components/TokenGate'
import { useTill } from '../src/api/context'

function Inside() {
  const { token } = useTill()
  return <p>inside with {token}</p>
}

describe('the token gate', () => {
  it('asks for a token before showing anything', () => {
    render(
      <TokenGate>
        <Inside />
      </TokenGate>,
    )

    expect(screen.getByLabelText('Token')).toBeInTheDocument()
    expect(screen.queryByText(/inside with/)).not.toBeInTheDocument()
  })

  it('keeps the token for the tab, not for the machine', async () => {
    render(
      <TokenGate>
        <Inside />
      </TokenGate>,
    )

    await userEvent.type(screen.getByLabelText('Token'), 'client-secret')
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))

    expect(screen.getByText('inside with client-secret')).toBeInTheDocument()
    // sessionStorage, so closing the tab is a logout everybody already knows how to perform.
    expect(sessionStorage.getItem('till.token')).toBe('client-secret')
    expect(localStorage.getItem('till.token')).toBeNull()
  })

  it('goes straight in when the tab already has one', () => {
    sessionStorage.setItem('till.token', 'from-earlier')

    render(
      <TokenGate>
        <Inside />
      </TokenGate>,
    )

    expect(screen.getByText('inside with from-earlier')).toBeInTheDocument()
  })

  it('will not accept whitespace as a token', async () => {
    render(
      <TokenGate>
        <Inside />
      </TokenGate>,
    )

    await userEvent.type(screen.getByLabelText('Token'), '   ')

    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled()
  })

  it('trims what was pasted, because a copied token brings a newline with it', async () => {
    render(
      <TokenGate>
        <Inside />
      </TokenGate>,
    )

    await userEvent.type(screen.getByLabelText('Token'), '  client-secret  ')
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }))

    expect(screen.getByText('inside with client-secret')).toBeInTheDocument()
  })

  it('says what the two tokens are for, rather than showing an empty panel later', () => {
    render(
      <TokenGate>
        <Inside />
      </TokenGate>,
    )

    expect(screen.getByText(/outbox panel needs the/)).toBeInTheDocument()
  })
})

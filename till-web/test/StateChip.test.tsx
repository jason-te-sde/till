import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StateChip } from '../src/components/StateChip'

describe('a reservation state', () => {
  it('is a word first, so it survives a screenshot and a colourblind reader', () => {
    render(<StateChip state="COMMITTED" />)

    expect(screen.getByText('COMMITTED')).toBeInTheDocument()
  })

  it('shows both states when the deadline disagrees with the row', () => {
    render(<StateChip state="HELD" effectiveState="EXPIRED" />)

    // The signature behaviour: the hold is expired because its deadline passed, and the row still
    // says HELD because nothing has needed to write it off. Hiding either would make the sweeper
    // look mandatory.
    expect(screen.getByText('EXPIRED')).toBeInTheDocument()
    expect(screen.getByText('(stored HELD)')).toBeInTheDocument()
  })

  it('says it once when they agree', () => {
    render(<StateChip state="HELD" effectiveState="HELD" />)

    expect(screen.getByText('HELD')).toBeInTheDocument()
    expect(screen.queryByText(/stored/)).not.toBeInTheDocument()
  })
})

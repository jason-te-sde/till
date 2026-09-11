import { render, type RenderResult } from '@testing-library/react'
import type { ReactElement } from 'react'
import { TillClient } from '../src/api/client'
import { TillContext } from '../src/api/context'
import { BASE } from './fixtures'

/** Renders inside a session, which every screen past the token gate has. */
export function renderWithTill(
  element: ReactElement,
  options: { token?: string } = {},
): RenderResult & { forgotten: () => boolean } {
  let forgot = false
  const client = new TillClient({
    baseUrl: BASE,
    token: options.token ?? 'secret',
    attempts: 1,
    sleep: () => Promise.resolve(),
  })
  const session = {
    client,
    token: options.token ?? 'secret',
    forget: () => {
      forgot = true
    },
  }
  return {
    ...render(<TillContext value={session}>{element}</TillContext>),
    forgotten: () => forgot,
  }
}

import { createContext, use } from 'react'
import type { TillClient } from './client'

export interface TillSession {
  readonly client: TillClient
  readonly token: string
  readonly forget: () => void
}

export const TillContext = createContext<TillSession | null>(null)

/**
 * The client for the current session.
 *
 * Throws rather than returning null: every screen inside the gate has a client, and a hook that can
 * return null makes every one of them check for a case that cannot happen.
 */
export function useTill(): TillSession {
  const session = use(TillContext)
  if (session === null) {
    throw new Error('useTill must be used inside a TokenGate')
  }
  return session
}

import { useMemo, useState, type ReactNode } from 'react'
import { TillClient } from '../api/client'
import { TillContext } from '../api/context'

const STORAGE_KEY = 'till.token'

/**
 * Asks for a bearer token and keeps it for the tab.
 *
 * **sessionStorage, not localStorage**, and not because it is more secure against a script on the
 * page — anything that can read one can read the other. It is because the lifetime is right: a token
 * pasted into a console should not still be there next week on a shared machine, and "close the tab"
 * is a logout everybody already knows how to perform.
 *
 * This is a console, and what it does here is the honest minimum. A product would not ask a person
 * for a bearer token at all: it would sign them in, keep a session cookie the page cannot read, and
 * put a small server in front that holds the token. That server is out of scope for this project, and
 * saying so is better than shipping something that looks like a login and is not.
 */
export function TokenGate({ children }: { children: ReactNode }) {
  const [token, setToken] = useState(() => sessionStorage.getItem(STORAGE_KEY) ?? '')
  const [draft, setDraft] = useState('')

  const session = useMemo(() => {
    if (token === '') {
      return null
    }
    return {
      client: new TillClient({ token }),
      token,
      forget: () => {
        sessionStorage.removeItem(STORAGE_KEY)
        setToken('')
      },
    }
  }, [token])

  if (session === null) {
    return (
      <main className="mx-auto flex min-h-dvh max-w-lg flex-col justify-center px-6">
        <h1 className="text-2xl font-semibold">till</h1>
        <p className="mt-2 text-ink-secondary">
          This console talks to the till API, which wants a bearer token.
        </p>

        <form
          className="mt-6"
          onSubmit={(event) => {
            event.preventDefault()
            const trimmed = draft.trim()
            if (trimmed === '') {
              return
            }
            sessionStorage.setItem(STORAGE_KEY, trimmed)
            setToken(trimmed)
          }}
        >
          <label htmlFor="token" className="block text-sm font-medium">
            Token
          </label>
          <input
            id="token"
            type="password"
            autoComplete="off"
            value={draft}
            onChange={(event) => {
              setDraft(event.target.value)
            }}
            placeholder="till.auth.client-token, or the admin one"
            className="mt-1 w-full rounded-md border border-line-strong bg-surface px-3 py-2 text-ink placeholder:text-ink-muted"
          />
          <button
            type="submit"
            className="mt-3 w-full rounded-md bg-ink px-3 py-2 font-medium text-surface disabled:opacity-50"
            disabled={draft.trim() === ''}
          >
            Continue
          </button>
        </form>

        <div className="mt-8 space-y-2 border-t border-hairline pt-6 text-sm text-ink-secondary">
          <p>
            The client token is enough for the shop. The operator view&rsquo;s outbox panel needs the
            admin token, and says so rather than showing an empty box.
          </p>
          <p>
            Kept in <code>sessionStorage</code>, so closing the tab forgets it. A product would sign
            you in and keep the token on a server instead of asking a person for one.
          </p>
        </div>
      </main>
    )
  }

  return <TillContext value={session}>{children}</TillContext>
}

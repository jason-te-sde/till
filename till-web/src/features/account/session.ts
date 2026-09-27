import { useGetSessionQuery } from '../../api/storeApi'
import type { Me } from '../../api/types'

/**
 * Where the browser goes to sign in: the store's own endpoint, which runs the OpenID Connect code flow
 * on the server and comes back to `returnTo` when it is done.
 *
 * A full navigation, never a fetch — the flow is a series of redirects through the identity provider's
 * login page, and the tokens it ends with stay on the server.
 *
 * @param returnTo the path to come back to; the store accepts only a path on this site
 * @returns the URL
 */
export function signInHref(returnTo: string): string {
  return `/oauth2/authorization/idp?returnTo=${encodeURIComponent(returnTo)}`
}

const SIGNED_OUT: Me = { authenticated: false, admin: false, name: null, email: null }

/**
 * Who is signed in.
 *
 * One cached request however many components ask, refreshed when the tab regains focus — so signing
 * out in another tab shows here the next time somebody looks.
 *
 * @returns the session, and whether it is still being asked for
 */
export function useSession(): { me: Me; loading: boolean } {
  const { data, isLoading } = useGetSessionQuery(undefined, { refetchOnFocus: true })
  return { me: data ?? SIGNED_OUT, loading: isLoading }
}

/**
 * @param me the session
 * @returns one or two letters for an avatar
 */
export function initials(me: Me): string {
  const source = (me.name ?? me.email ?? '?').trim()
  const words = source.split(/[\s@._-]+/).filter((word) => word !== '')
  const letters = words.length > 1 ? `${words[0]?.[0] ?? ''}${words[1]?.[0] ?? ''}` : source.slice(0, 2)
  return letters.toUpperCase()
}

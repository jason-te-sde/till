import { useEffect, useId, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router'
import { problemOf } from '../../api/problem'
import { useLogoutMutation } from '../../api/storeApi'
import { useAppDispatch } from '../../app/hooks'
import { leaveFor } from '../../app/navigation'
import { toastShown } from '../../app/uiSlice'
import { button } from '../../components/button'
import { Icon } from '../../components/Icon'
import { ThemeSwitch } from '../../components/ThemeSwitch'
import { initials, signInHref, useSession } from './session'

/**
 * The header's account control: "Sign in" for a visitor; for a customer, a menu with their orders, the
 * operator console if they may use it, the theme, and signing out.
 */
export function AccountMenu() {
  const { me, loading } = useSession()
  const location = useLocation()
  const [open, setOpen] = useState(false)
  const menu = useId()
  const root = useRef<HTMLDivElement>(null)
  const [logout, { isLoading: signingOut }] = useLogoutMutation()
  const dispatch = useAppDispatch()

  useEffect(() => {
    if (!open) {
      return
    }
    function onPointer(event: PointerEvent) {
      if (root.current !== null && !root.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false)
      }
    }
    document.addEventListener('pointerdown', onPointer)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onPointer)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  async function signOut() {
    try {
      const { redirect } = await logout().unwrap()
      // The provider's logout, so its session ends too — otherwise "Sign in" would sign straight back
      // in without asking, which on a shared computer is the opposite of signing out.
      leaveFor(redirect)
    } catch (error) {
      const problem = problemOf(error as Parameters<typeof problemOf>[0])
      dispatch(toastShown({ tone: 'error', title: 'Could not sign out', message: problem?.detail ?? 'Please try again.' }))
    }
  }

  if (loading) {
    return <span aria-hidden="true" className="skeleton size-9 rounded-full" />
  }

  if (!me.authenticated) {
    return (
      <a href={signInHref(`${location.pathname}${location.search}`)} className={button('secondary', 'sm', 'h-9')}>
        <Icon name="user" className="size-4" />
        Sign in
      </a>
    )
  }

  return (
    <div ref={root} className="relative">
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={menu}
        aria-label={`Account: ${me.name ?? 'signed in'}`}
        onClick={() => {
          setOpen((current) => !current)
        }}
        className="grid size-9 place-items-center rounded-full bg-accent text-sm font-bold text-accent-ink ring-offset-2 ring-offset-plane hover:ring-2 hover:ring-accent"
      >
        {initials(me)}
      </button>
      {open && (
        <div
          id={menu}
          role="menu"
          className="absolute right-0 z-40 mt-2 w-64 animate-rise overflow-hidden rounded-xl border border-hairline bg-raised shadow-pop"
        >
          <div className="border-b border-hairline px-4 py-3">
            <p className="truncate text-sm font-semibold">{me.name}</p>
            {me.email !== null && <p className="truncate text-xs text-ink-muted">{me.email}</p>}
          </div>
          <div className="p-1.5">
            <MenuLink
              to="/orders"
              icon="list"
              onClick={() => {
                setOpen(false)
              }}
            >
              Your orders
            </MenuLink>
            {me.admin && (
              <MenuLink
                to="/ops"
                icon="box"
                onClick={() => {
                  setOpen(false)
                }}
              >
                Operator console
              </MenuLink>
            )}
          </div>
          <div className="flex items-center justify-between border-t border-hairline px-4 py-2.5">
            <span className="text-xs text-ink-muted">Theme</span>
            <ThemeSwitch />
          </div>
          <div className="border-t border-hairline p-1.5">
            <button
              type="button"
              role="menuitem"
              disabled={signingOut}
              onClick={() => {
                void signOut()
              }}
              className="flex w-full items-center gap-2.5 rounded-lg px-3 py-2 text-left text-sm text-ink-secondary hover:bg-sunken hover:text-ink disabled:opacity-50"
            >
              <Icon name="logout" className="size-4" />
              {signingOut ? 'Signing out…' : 'Sign out'}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

function MenuLink({
  to,
  icon,
  onClick,
  children,
}: {
  to: string
  icon: 'list' | 'box'
  onClick: () => void
  children: string
}) {
  return (
    <Link
      to={to}
      role="menuitem"
      onClick={onClick}
      className="flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm text-ink-secondary hover:bg-sunken hover:text-ink"
    >
      <Icon name={icon} className="size-4" />
      {children}
    </Link>
  )
}

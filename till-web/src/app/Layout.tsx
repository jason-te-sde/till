import { useEffect } from 'react'
import { Link, Outlet, useLocation, useSearchParams } from 'react-router'
import { ErrorBoundary } from '../components/ErrorBoundary'
import { Icon } from '../components/Icon'
import { ThemeSwitch } from '../components/ThemeSwitch'
import { Toasts } from '../components/Toasts'
import { AccountMenu } from '../features/account/AccountMenu'
import { selectCartCount } from '../features/cart/cartSlice'
import { CartDrawer } from '../features/cart/CartDrawer'
import { useAppDispatch, useAppSelector } from './hooks'
import { SearchBox } from './SearchBox'
import { cartOpened, toastShown } from './uiSlice'

/**
 * Every page's frame: the header with search, cart and account; the footer; the cart drawer and the
 * toasts, which any page can open.
 */
export function Layout() {
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const dispatch = useAppDispatch()

  // A new page starts at the top, as a page load would.
  useEffect(() => {
    window.scrollTo(0, 0)
  }, [location.pathname])

  // The store sends a failed or cancelled sign-in back here with a flag rather than to an error page.
  useEffect(() => {
    if (params.get('signin') === 'failed') {
      dispatch(toastShown({ tone: 'error', title: "Sign-in didn't complete", message: 'Nothing was changed. Please try again.' }))
      const rest = new URLSearchParams(params)
      rest.delete('signin')
      setParams(rest, { replace: true })
    }
  }, [params, setParams, dispatch])

  const query = location.pathname === '/browse' ? (params.get('q') ?? '') : ''

  return (
    <div className="flex min-h-dvh flex-col">
      <a
        href="#main"
        className="sr-only z-50 rounded-lg bg-accent px-4 py-2 font-semibold text-accent-ink focus:not-sr-only focus:fixed focus:top-3 focus:left-3"
      >
        Skip to content
      </a>
      <header className="sticky top-0 z-30 border-b border-hairline bg-plane/85 backdrop-blur-md">
        <div className="mx-auto flex h-16 max-w-7xl items-center gap-4 px-4 sm:px-6">
          <Link to="/" className="flex shrink-0 items-center gap-2.5" aria-label="till games, home">
            <Logo />
            <span className="text-lg font-extrabold tracking-tight">
              till<span className="font-medium text-ink-muted"> games</span>
            </span>
          </Link>
          <nav aria-label="Store" className="ml-4 hidden items-center gap-1 md:flex">
            <NavItem to="/browse">Browse</NavItem>
            <NavItem to="/browse?onSale=true">On sale</NavItem>
            <NavItem to="/browse?sort=newest">New</NavItem>
          </nav>
          <div className="ml-auto hidden max-w-md flex-1 sm:block">
            {/* Keyed by the query, so arriving at a new search resets what the box shows. */}
            <SearchBox key={query} initial={query} />
          </div>
          <div className="ml-auto flex items-center gap-2 sm:ml-2">
            <CartButton />
            <AccountMenu />
          </div>
        </div>
        <div className="px-4 pb-3 sm:hidden">
          <SearchBox key={query} initial={query} />
        </div>
      </header>

      <main id="main" className="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6">
        {/* Keyed by the path, so a page that failed does not stay failed after navigating away. */}
        <ErrorBoundary key={location.pathname}>
          <Outlet />
        </ErrorBoundary>
      </main>

      <footer className="mt-16 border-t border-hairline">
        <div className="mx-auto grid max-w-7xl gap-8 px-4 py-10 text-sm sm:px-6 md:grid-cols-[2fr_1fr_1fr]">
          <div>
            <div className="flex items-center gap-2.5">
              <Logo />
              <span className="font-extrabold">till games</span>
            </div>
            <p className="mt-3 max-w-sm text-ink-secondary">
              A demonstration game store built on till, an inventory reservation ledger. Stock is really held
              and really sold; payment is simulated.
            </p>
            <div className="mt-4">
              <ThemeSwitch />
            </div>
          </div>
          <div>
            <p className="font-semibold">Store</p>
            <ul className="mt-3 space-y-2 text-ink-secondary">
              <li><Link to="/browse" className="hover:text-ink">All games</Link></li>
              <li><Link to="/browse?onSale=true" className="hover:text-ink">On sale</Link></li>
              <li><Link to="/orders" className="hover:text-ink">Your orders</Link></li>
            </ul>
          </div>
          <div>
            <p className="font-semibold">Developers</p>
            <ul className="mt-3 space-y-2 text-ink-secondary">
              <li><a href="/swagger-ui/index.html" className="hover:text-ink">API reference</a></li>
              <li><a href="https://github.com/jason-te-sde/till" className="hover:text-ink" rel="noreferrer">Source code</a></li>
            </ul>
          </div>
        </div>
      </footer>

      <CartDrawer />
      <Toasts />
    </div>
  )
}

function NavItem({ to, children }: { to: string; children: string }) {
  return (
    <Link to={to} className="rounded-lg px-3 py-2 text-sm font-medium text-ink-secondary transition hover:bg-sunken hover:text-ink">
      {children}
    </Link>
  )
}

function CartButton() {
  const count = useAppSelector(selectCartCount)
  const dispatch = useAppDispatch()
  return (
    <button
      type="button"
      onClick={() => {
        dispatch(cartOpened())
      }}
      aria-label={count === 0 ? 'Cart, empty' : `Cart, ${String(count)} ${count === 1 ? 'item' : 'items'}`}
      className="relative grid size-10 place-items-center rounded-xl text-ink-secondary transition hover:bg-sunken hover:text-ink"
    >
      <Icon name="cart" className="size-6" />
      {count > 0 && (
        <span className="numeric absolute -top-0.5 -right-0.5 grid h-5 min-w-5 place-items-center rounded-full bg-accent px-1 text-[11px] font-bold text-accent-ink">
          {count > 99 ? '99+' : count}
        </span>
      )}
    </button>
  )
}

function Logo() {
  return (
    <svg viewBox="0 0 32 32" className="size-8 shrink-0" aria-hidden="true">
      <defs>
        <linearGradient id="till-logo" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#8f79ff" />
          <stop offset="1" stopColor="#4a2ee0" />
        </linearGradient>
      </defs>
      <rect width="32" height="32" rx="9" fill="url(#till-logo)" />
      <path d="M13 8.5v11.2c0 2.3 1.2 3.4 3.4 3.4H20M9.5 13h10" fill="none" stroke="#fff" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="22.5" cy="10" r="2" fill="#4ade80" />
    </svg>
  )
}

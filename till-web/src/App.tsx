import { NavLink, Navigate, Route, Routes } from 'react-router'
import { TokenGate } from './components/TokenGate'
import { useTill } from './api/context'
import { ShopPage } from './shop/ShopPage'
import { OpsPage } from './ops/OpsPage'
import { ThemeToggle } from './components/ThemeToggle'

export function App() {
  return (
    <TokenGate>
      <Shell />
    </TokenGate>
  )
}

function Shell() {
  const { forget } = useTill()

  return (
    <div className="min-h-dvh">
      <header className="border-b border-hairline bg-surface">
        <div className="mx-auto flex max-w-6xl items-center gap-4 px-6 py-3">
          <span className="font-semibold">till</span>
          <nav className="flex gap-1" aria-label="Sections">
            <Tab to="/shop">Shop</Tab>
            <Tab to="/ops">Ledger</Tab>
          </nav>
          <div className="ml-auto flex items-center gap-2">
            <ThemeToggle />
            <button
              type="button"
              onClick={forget}
              className="rounded-md border border-line-strong px-3 py-1.5 text-sm font-medium hover:bg-sunken"
            >
              Forget token
            </button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-6xl px-6 py-6">
        <Routes>
          <Route path="/" element={<Navigate to="/shop" replace />} />
          <Route path="/shop" element={<ShopPage />} />
          <Route path="/ops" element={<OpsPage />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
    </div>
  )
}

function Tab({ to, children }: { to: string; children: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        `rounded-md px-3 py-1.5 text-sm font-medium ${
          isActive ? 'bg-ink text-surface' : 'text-ink-secondary hover:bg-sunken'
        }`
      }
    >
      {children}
    </NavLink>
  )
}

function NotFound() {
  return (
    <div className="py-16 text-center">
      <p className="text-lg font-medium">No such page</p>
      <p className="mt-1 text-ink-secondary">
        The console has two: the shop and the ledger.
      </p>
    </div>
  )
}

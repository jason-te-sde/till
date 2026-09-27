import { lazy, Suspense } from 'react'
import { Link, Route, Routes } from 'react-router'
import { button } from '../components/button'
import { EmptyState, Skeleton } from '../components/States'
import { BrowsePage } from '../features/catalogue/BrowsePage'
import { GamePage } from '../features/catalogue/GamePage'
import { HomePage } from '../features/catalogue/HomePage'
import { CheckoutPage } from '../features/checkout/CheckoutPage'
import { OrderPage } from '../features/orders/OrderPage'
import { OrdersPage } from '../features/orders/OrdersPage'
import { Layout } from './Layout'
import { useTitle } from './useTitle'

// Split out: only operators ever open it, so no customer should download it.
const OpsPage = lazy(() => import('../features/ops/OpsPage').then((module) => ({ default: module.OpsPage })))

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<HomePage />} />
        <Route path="browse" element={<BrowsePage />} />
        <Route path="games/:sku" element={<GamePage />} />
        <Route path="checkout" element={<CheckoutPage />} />
        <Route path="orders" element={<OrdersPage />} />
        <Route path="orders/:id" element={<OrderPage />} />
        <Route
          path="ops"
          element={
            <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
              <OpsPage />
            </Suspense>
          }
        />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

function NotFoundPage() {
  useTitle('Page not found')
  return (
    <EmptyState
      icon="gamepad"
      title="There's nothing on this page"
      action={
        <Link to="/" className={button('primary')}>
          Back to the store
        </Link>
      }
    >
      The link may be old, or mistyped. Everything we sell is a search away.
    </EmptyState>
  )
}

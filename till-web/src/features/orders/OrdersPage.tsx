import { Link } from 'react-router'
import { problemOf } from '../../api/problem'
import { useListOrdersQuery } from '../../api/storeApi'
import type { Order } from '../../api/types'
import { useTitle } from '../../app/useTitle'
import { button } from '../../components/button'
import { Icon } from '../../components/Icon'
import { EmptyState, ErrorNotice, Skeleton } from '../../components/States'
import { dateTime, money, plural } from '../../format'
import { SignInPrompt } from '../account/SignInPrompt'
import { useSession } from '../account/session'
import { GameCover } from '../catalogue/GameCover'
import { Countdown } from './Countdown'
import { OrderStatusBadge } from './OrderStatusBadge'

/** Every order this customer has placed, newest first. */
export function OrdersPage() {
  useTitle('Your orders')
  const { me, loading } = useSession()
  const { data, error, refetch } = useListOrdersQuery(undefined, { skip: !me.authenticated, refetchOnMountOrArgChange: true })
  const problem = problemOf(error)

  if (loading) {
    return <Skeleton className="mx-auto h-80 max-w-3xl rounded-2xl" />
  }
  if (!me.authenticated) {
    return (
      <SignInPrompt title="Sign in to see your orders" returnTo="/orders">
        Your orders are kept with your account, so you can see them from any device.
      </SignInPrompt>
    )
  }

  return (
    <div className="mx-auto max-w-3xl">
      <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">Your orders</h1>
      <div className="mt-6">
        {problem !== undefined && data === undefined ? (
          <ErrorNotice
            problem={problem}
            onRetry={() => {
              void refetch()
            }}
          />
        ) : data === undefined ? (
          <div className="space-y-3">
            {Array.from({ length: 3 }, (_, i) => (
              <Skeleton key={i} className="h-28 rounded-2xl" />
            ))}
          </div>
        ) : data.items.length === 0 ? (
          <EmptyState
            icon="list"
            title="No orders yet"
            action={
              <Link to="/browse" className={button('primary')}>
                Browse the store
              </Link>
            }
          >
            When you buy a game, it will show up here.
          </EmptyState>
        ) : (
          <ul className="space-y-3">
            {data.items.map((order) => (
              <li key={order.id}>
                <OrderRow order={order} />
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

function OrderRow({ order }: { order: Order }) {
  const [first, ...rest] = order.lines
  const copies = order.lines.reduce((count, line) => count + line.quantity, 0)
  return (
    <Link
      to={`/orders/${order.id}`}
      className="group flex items-center gap-4 rounded-2xl border border-hairline bg-surface p-4 transition hover:border-line-strong hover:shadow-card"
    >
      <div className="flex shrink-0 -space-x-6">
        {order.lines.slice(0, 3).map((line) => (
          <GameCover key={line.sku} sku={line.sku} motif={line.cover ?? 'orbit'} className="h-20 w-15 rounded-lg ring-2 ring-surface" />
        ))}
      </div>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <OrderStatusBadge status={order.status} />
          {order.status === 'PENDING' && (
            <span className="text-xs text-ink-secondary">
              held for <Countdown deadline={order.expiresAt} />
            </span>
          )}
        </div>
        <p className="mt-1.5 truncate font-semibold group-hover:text-accent">
          {first?.title}
          {rest.length > 0 && <span className="font-normal text-ink-secondary"> and {plural(rest.length, 'more game')}</span>}
        </p>
        <p className="mt-0.5 text-xs text-ink-muted">
          {dateTime(order.createdAt)} · {plural(copies, 'copy', 'copies')}
        </p>
      </div>
      <div className="flex shrink-0 items-center gap-3">
        <span className="numeric font-bold">{money(order.totalCents, order.currency)}</span>
        <Icon name="chevronRight" className="size-5 text-ink-muted" />
      </div>
    </Link>
  )
}

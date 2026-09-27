import { useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { problemOf } from '../../api/problem'
import { useGetOrderQuery } from '../../api/storeApi'
import type { OrderStatus } from '../../api/types'
import { useAppDispatch } from '../../app/hooks'
import { toastShown } from '../../app/uiSlice'
import { useTitle } from '../../app/useTitle'
import { button } from '../../components/button'
import { Icon } from '../../components/Icon'
import { EmptyState, ErrorNotice, Skeleton } from '../../components/States'
import { dateTime } from '../../format'
import { SignInPrompt } from '../account/SignInPrompt'
import { useSession } from '../account/session'
import { HeldOrder } from './HeldOrder'
import { OrderLines } from './OrderLines'
import { OrderStatusBadge } from './OrderStatusBadge'

/**
 * One order: what it was for, what it cost, where it stands — and, while its stock is still held, the
 * way to pay for it. The page a successful checkout lands on.
 */
export function OrderPage() {
  const { id = '' } = useParams()
  const location = useLocation()
  const justPaid = (location.state as { justPaid?: boolean } | null)?.justPaid === true
  const { me, loading } = useSession()
  const { data: order, error, refetch } = useGetOrderQuery(id, { skip: !me.authenticated })
  const dispatch = useAppDispatch()
  const [paidHere, setPaidHere] = useState(false)
  useTitle(order === undefined ? 'Order' : `Order ${order.id.slice(0, 8)}`)
  const problem = problemOf(error)

  if (loading) {
    return <Skeleton className="mx-auto h-96 max-w-3xl rounded-2xl" />
  }
  if (!me.authenticated) {
    return (
      <SignInPrompt title="Sign in to see this order" returnTo={`/orders/${id}`}>
        Orders are private to the account that placed them.
      </SignInPrompt>
    )
  }
  if (problem?.code === 'NOT_FOUND') {
    return (
      <EmptyState
        icon="search"
        title="We couldn't find that order"
        action={
          <Link to="/orders" className={button('primary')}>
            Your orders
          </Link>
        }
      >
        It may belong to a different account.
      </EmptyState>
    )
  }
  if (order === undefined) {
    return problem !== undefined ? (
      <ErrorNotice
        problem={problem}
        onRetry={() => {
          void refetch()
        }}
      />
    ) : (
      <Skeleton className="mx-auto h-96 max-w-3xl rounded-2xl" />
    )
  }

  return (
    <div className="mx-auto max-w-4xl">
      <Link to="/orders" className="inline-flex items-center gap-1 text-sm font-medium text-ink-secondary hover:text-ink">
        <Icon name="chevronLeft" className="size-4" />
        Your orders
      </Link>

      {(justPaid || paidHere) && order.status === 'PAID' && (
        <div role="status" className="mt-5 flex items-center gap-4 rounded-2xl border border-hairline bg-surface p-5 shadow-card">
          <span className="grid size-12 shrink-0 place-items-center rounded-full bg-good text-white">
            <Icon name="check" className="size-7" />
          </span>
          <div>
            <p className="text-lg font-bold">Thank you — your order is complete</p>
            <p className="text-sm text-ink-secondary">The copies are yours. A receipt is below.</p>
          </div>
        </div>
      )}

      <div className={`mt-6 grid gap-8 ${order.status === 'PENDING' ? 'lg:grid-cols-[1fr_24rem]' : ''}`}>
        <section className="rounded-2xl border border-hairline bg-surface p-6">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h1 className="text-2xl font-bold tracking-tight">
                Order <span className="font-mono">{order.id.slice(0, 8)}</span>
              </h1>
              <p className="mt-1 text-sm text-ink-secondary">Placed {dateTime(order.createdAt)}</p>
            </div>
            <OrderStatusBadge status={order.status} />
          </div>
          <div className="mt-6">
            <OrderLines order={order} />
          </div>
          <dl className="mt-6 grid gap-x-6 gap-y-2 border-t border-hairline pt-4 text-sm sm:grid-cols-2">
            <div className="flex justify-between gap-3 sm:block">
              <dt className="text-ink-muted">Order number</dt>
              <dd className="font-mono text-xs break-all sm:mt-0.5">{order.id}</dd>
            </div>
            {order.closedAt !== null && (
              <div className="flex justify-between gap-3 sm:block">
                <dt className="text-ink-muted">{closedLabel(order.status)}</dt>
                <dd className="sm:mt-0.5">{dateTime(order.closedAt)}</dd>
              </div>
            )}
          </dl>
        </section>

        {order.status === 'PENDING' && (
          <HeldOrder
            order={order}
            onPaid={() => {
              setPaidHere(true)
              dispatch(toastShown({ tone: 'success', title: 'Payment complete', message: 'Your games are yours.' }))
            }}
            onCancelled={() => {
              dispatch(toastShown({ tone: 'info', title: 'Order cancelled', message: 'The copies were released.' }))
            }}
            onExpired={() => {
              void refetch()
            }}
          />
        )}
      </div>
    </div>
  )
}

function closedLabel(status: OrderStatus): string {
  switch (status) {
    case 'PAID':
      return 'Paid'
    case 'CANCELLED':
      return 'Cancelled'
    default:
      return 'Expired'
  }
}

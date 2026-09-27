import { useCallback, useEffect } from 'react'
import { Link, useNavigate } from 'react-router'
import { problemOf } from '../../api/problem'
import { useGetOrderQuery, usePlaceOrderMutation } from '../../api/storeApi'
import type { Order } from '../../api/types'
import { useAppDispatch, useAppSelector } from '../../app/hooks'
import { cartOpened, toastShown } from '../../app/uiSlice'
import { useTitle } from '../../app/useTitle'
import { button } from '../../components/button'
import { Icon } from '../../components/Icon'
import { EmptyState, ErrorNotice, Skeleton } from '../../components/States'
import { money, plural } from '../../format'
import { SignInPrompt } from '../account/SignInPrompt'
import { useSession } from '../account/session'
import { cleared, selectCartLines, selectCartSavings, selectCartSubtotal, type CartLine } from '../cart/cartSlice'
import { GameCover } from '../catalogue/GameCover'
import { HeldOrder } from '../orders/HeldOrder'
import { OrderLines } from '../orders/OrderLines'
import { ShortfallNotice } from './ShortfallNotice'
import { fingerprint, useCheckoutAttempt, type CheckoutAttempt } from './useCheckoutAttempt'

/**
 * Checkout, in two steps the customer can see: review the cart and place the order, which holds the
 * stock; then pay for it before the hold runs out.
 *
 * Signing in comes first, because an order belongs to somebody — and the cart survives the trip to the
 * identity provider, because it lives in this browser, not in the session.
 */
export function CheckoutPage() {
  useTitle('Checkout')
  const lines = useAppSelector(selectCartLines)
  const { me, loading } = useSession()
  const attempt = useCheckoutAttempt(lines)

  if (loading) {
    return <Skeleton className="mx-auto h-96 max-w-4xl rounded-2xl" />
  }
  if (!me.authenticated) {
    return (
      <SignInPrompt title="Sign in to check out" returnTo="/checkout">
        Your cart is saved on this device, and will be waiting for you when you come back.
      </SignInPrompt>
    )
  }
  if (attempt.orderId !== undefined) {
    return <PayStep orderId={attempt.orderId} attempt={attempt} />
  }
  if (lines.length === 0) {
    return (
      <EmptyState
        icon="cart"
        title="Your cart is empty"
        action={
          <Link to="/browse" className={button('primary')}>
            Find something to play
          </Link>
        }
      >
        Add a game or two, then come back here to check out.
      </EmptyState>
    )
  }
  // Keyed by the cart, so an edit clears the last attempt's error along with its idempotency key.
  return <ReviewStep key={fingerprint(lines)} lines={lines} attempt={attempt} />
}

function Steps({ current }: { current: 1 | 2 }) {
  const steps = ['Review & hold', 'Pay', 'Done']
  return (
    <ol className="mb-8 flex items-center gap-3 text-sm" aria-label="Checkout progress">
      {steps.map((step, i) => {
        const index = i + 1
        const done = index < current
        const active = index === current
        return (
          <li key={step} className="flex items-center gap-3" aria-current={active ? 'step' : undefined}>
            <span
              className={`grid size-7 place-items-center rounded-full text-xs font-bold ${
                done ? 'bg-good text-white' : active ? 'bg-accent text-accent-ink' : 'bg-sunken text-ink-muted'
              }`}
            >
              {done ? <Icon name="check" className="size-4" /> : index}
            </span>
            <span className={active ? 'font-semibold text-ink' : 'text-ink-secondary'}>{step}</span>
            {index < steps.length && <span aria-hidden="true" className="h-px w-8 bg-line-strong sm:w-14" />}
          </li>
        )
      })}
    </ol>
  )
}

function ReviewStep({ lines, attempt }: { lines: readonly CartLine[]; attempt: CheckoutAttempt }) {
  const [placeOrder, { isLoading, error }] = usePlaceOrderMutation()
  const subtotal = useAppSelector(selectCartSubtotal)
  const savings = useAppSelector(selectCartSavings)
  const dispatch = useAppDispatch()
  const problem = problemOf(error)

  async function place() {
    try {
      const order = await placeOrder({
        key: attempt.key,
        lines: lines.map((line) => ({ sku: line.sku, quantity: line.quantity })),
      }).unwrap()
      attempt.placed(order.id)
    } catch {
      // Shown below, from the mutation's own error state.
    }
  }

  return (
    <div className="mx-auto max-w-5xl">
      <Steps current={1} />
      <div className="grid gap-8 lg:grid-cols-[1fr_22rem]">
        <section aria-labelledby="review-heading">
          <div className="flex items-center justify-between">
            <h1 id="review-heading" className="text-2xl font-bold tracking-tight">
              Review your order
            </h1>
            <button
              type="button"
              onClick={() => {
                dispatch(cartOpened())
              }}
              className="text-sm font-semibold text-accent hover:underline"
            >
              Edit cart
            </button>
          </div>
          <ul className="mt-5 divide-y divide-hairline rounded-2xl border border-hairline bg-surface px-5">
            {lines.map((line) => (
              <li key={line.sku} className="flex items-center gap-4 py-4">
                <GameCover sku={line.sku} motif={line.cover} className="h-20 w-15 shrink-0 rounded-lg" />
                <div className="min-w-0 flex-1">
                  <p className="truncate font-semibold">{line.title}</p>
                  <p className="text-sm text-ink-muted">
                    {line.quantity} × {money(line.priceCents)}
                  </p>
                </div>
                <p className="numeric font-semibold">{money(line.priceCents * line.quantity)}</p>
              </li>
            ))}
          </ul>
          {problem !== undefined && (
            <div className="mt-5">
              {problem.code === 'INSUFFICIENT_STOCK' && problem.shortfalls !== undefined ? (
                <ShortfallNotice shortfalls={problem.shortfalls} lines={lines} />
              ) : (
                <ErrorNotice
                  problem={problem}
                  onRetry={() => {
                    void place()
                  }}
                />
              )}
            </div>
          )}
        </section>

        <aside aria-label="Order summary" className="h-fit rounded-2xl border border-hairline bg-surface p-6 shadow-card lg:sticky lg:top-24">
          <h2 className="font-semibold">Summary</h2>
          <dl className="mt-4 space-y-2 text-sm">
            <div className="flex justify-between">
              <dt className="text-ink-secondary">{plural(lines.reduce((n, line) => n + line.quantity, 0), 'item')}</dt>
              <dd className="numeric">{money(subtotal + savings)}</dd>
            </div>
            {savings > 0 && (
              <div className="flex justify-between text-good-ink">
                <dt>Discounts</dt>
                <dd className="numeric">−{money(savings)}</dd>
              </div>
            )}
            <div className="flex justify-between border-t border-hairline pt-3 text-base font-bold">
              <dt>Total</dt>
              <dd className="numeric">{money(subtotal)}</dd>
            </div>
          </dl>
          <button
            type="button"
            disabled={isLoading}
            onClick={() => {
              void place()
            }}
            className={button('primary', 'lg', 'mt-6 w-full')}
          >
            {isLoading ? 'Holding your copies…' : 'Place order'}
          </button>
          <ul className="mt-4 space-y-2 text-xs text-ink-secondary">
            <li className="flex gap-2">
              <Icon name="clock" className="size-4 shrink-0 text-ink-muted" />
              Placing the order holds your copies for 15 minutes; you pay on the next step.
            </li>
            <li className="flex gap-2">
              <Icon name="tag" className="size-4 shrink-0 text-ink-muted" />
              Prices are confirmed by the store when the order is placed.
            </li>
          </ul>
        </aside>
      </div>
    </div>
  )
}

function PayStep({ orderId, attempt }: { orderId: string; attempt: CheckoutAttempt }) {
  const { data: order, error, refetch } = useGetOrderQuery(orderId, { pollingInterval: 10_000, skipPollingIfUnfocused: true })
  const dispatch = useAppDispatch()
  const navigate = useNavigate()
  const problem = problemOf(error)
  const { finished } = attempt

  const complete = useCallback(
    (paid: Order) => {
      dispatch(cleared())
      finished()
      dispatch(toastShown({ tone: 'success', title: 'Payment complete', message: 'Your games are yours.' }))
      void navigate(`/orders/${paid.id}`, { replace: true, state: { justPaid: true } })
    },
    [dispatch, finished, navigate],
  )

  // Paid somewhere else in the meantime — another tab, or a retry whose answer arrived late.
  useEffect(() => {
    if (order?.status === 'PAID') {
      complete(order)
    }
  }, [order, complete])

  // The order this attempt remembers is gone — not this customer's, or the store's data was reset.
  useEffect(() => {
    if (problem?.code === 'NOT_FOUND') {
      finished()
    }
  }, [problem?.code, finished])

  if (order === undefined) {
    return problem !== undefined && problem.code !== 'NOT_FOUND' ? (
      <ErrorNotice
        problem={problem}
        onRetry={() => {
          void refetch()
        }}
      />
    ) : (
      <Skeleton className="mx-auto h-96 max-w-4xl rounded-2xl" />
    )
  }

  if (order.status === 'EXPIRED' || order.status === 'CANCELLED') {
    const expired = order.status === 'EXPIRED'
    return (
      <div className="mx-auto max-w-xl">
        <EmptyState
          icon={expired ? 'clock' : 'x'}
          title={expired ? 'Your hold ran out' : 'Order cancelled'}
          action={
            <button type="button" onClick={finished} className={button('primary')}>
              {expired ? 'Place the order again' : 'Back to my cart'}
            </button>
          }
        >
          {expired
            ? 'The copies went back on sale when the timer reached zero. Your cart is still here — try again and we will hold them afresh.'
            : 'The copies were released. Your cart is still here if you change your mind.'}
        </EmptyState>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-5xl">
      <Steps current={2} />
      <div className="grid gap-8 lg:grid-cols-[1fr_24rem]">
        <section aria-labelledby="pay-heading" className="rounded-2xl border border-hairline bg-surface p-6">
          <h1 id="pay-heading" className="text-2xl font-bold tracking-tight">
            Your order is held
          </h1>
          <p className="mt-1 text-sm text-ink-secondary">
            Order <span className="font-mono">{order.id.slice(0, 8)}</span>
          </p>
          <div className="mt-5">
            <OrderLines order={order} />
          </div>
        </section>
        <HeldOrder
          order={order}
          onPaid={complete}
          onCancelled={() => {
            dispatch(toastShown({ tone: 'info', title: 'Order cancelled', message: 'The copies were released.' }))
            finished()
          }}
          onExpired={() => {
            void refetch()
          }}
        />
      </div>
    </div>
  )
}

import { useState } from 'react'
import { Link } from 'react-router'
import { problemOf } from '../../api/problem'
import { useOpsOutboxQuery, useOpsReservationsQuery, useOpsStockQuery } from '../../api/storeApi'
import type { OpsStock, ReservationState } from '../../api/types'
import { useTitle } from '../../app/useTitle'
import { button } from '../../components/button'
import { EmptyState, ErrorNotice, Skeleton } from '../../components/States'
import { ago, compact, dateTime } from '../../format'
import { SignInPrompt } from '../account/SignInPrompt'
import { useSession } from '../account/session'
import { Countdown } from '../orders/Countdown'
import { AdjustDialog } from './AdjustDialog'
import { StateChip } from './StateChip'
import { StockLegend, StockMeter } from './StockMeter'

/** Live, but not frantic: an operator watching holds come and go, not a trading screen. */
const POLL_MS = 3000
const polling = { pollingInterval: POLL_MS, skipPollingIfUnfocused: true } as const

type Tab = 'stock' | 'reservations' | 'outbox'

/**
 * The operator console: the ledger as the store's admins see it.
 *
 * It reaches the ledger through the store, never directly — the browser holds no ledger token, and
 * `/api/ops` answers only members of the admin group. Hiding this page from everybody else is a
 * courtesy; refusing them is the server's job, and it does.
 */
export function OpsPage() {
  useTitle('Operator console')
  const { me, loading } = useSession()
  const [tab, setTab] = useState<Tab>('stock')

  if (loading) {
    return <Skeleton className="h-96 rounded-2xl" />
  }
  if (!me.authenticated) {
    return (
      <SignInPrompt title="Operators only" returnTo="/ops">
        The operator console is for members of the store&apos;s admin group. Sign in with an operator account.
      </SignInPrompt>
    )
  }
  if (!me.admin) {
    return (
      <EmptyState
        icon="shield"
        title="This page is for operators"
        action={
          <Link to="/" className={button('primary')}>
            Back to the store
          </Link>
        }
      >
        Your account isn&apos;t in the store&apos;s admin group, so the operator console isn&apos;t available to it.
      </EmptyState>
    )
  }

  return (
    <div>
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-sm font-semibold text-accent">Operator console</p>
          <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">The ledger, live</h1>
          <p className="mt-1 text-sm text-ink-secondary">Stock, holds and the event outbox, refreshed every few seconds.</p>
        </div>
      </div>

      <Summary />

      <div role="tablist" aria-label="Views" className="mt-8 flex gap-1 border-b border-hairline">
        {(
          [
            ['stock', 'Stock'],
            ['reservations', 'Reservations'],
            ['outbox', 'Outbox'],
          ] as const
        ).map(([value, label]) => (
          <button
            key={value}
            type="button"
            role="tab"
            aria-selected={tab === value}
            onClick={() => {
              setTab(value)
            }}
            className={`-mb-px border-b-2 px-4 py-2.5 text-sm font-semibold transition ${
              tab === value ? 'border-accent text-ink' : 'border-transparent text-ink-secondary hover:text-ink'
            }`}
          >
            {label}
          </button>
        ))}
      </div>
      <div role="tabpanel" className="mt-6">
        {tab === 'stock' && <StockView />}
        {tab === 'reservations' && <ReservationsView />}
        {tab === 'outbox' && <OutboxView />}
      </div>
    </div>
  )
}

/**
 * The four numbers an operator looks at first. Bare tiles — a label, a value, a line saying what it
 * means — with no sparkline and no delta, because nothing records a history to compare against.
 */
function Summary() {
  const stock = useOpsStockQuery({ limit: 500 }, polling)
  const outbox = useOpsOutboxQuery({ limit: 1 }, polling)
  const items = stock.data?.items ?? []
  const tiles = [
    { label: 'Games stocked', value: items.length, caption: 'SKUs with a stock row' },
    { label: 'On hand', value: items.reduce((n, s) => n + s.onHand, 0), caption: 'units physically held' },
    { label: 'Held', value: items.reduce((n, s) => n + s.reserved, 0), caption: 'units promised to open orders' },
    { label: 'Outbox', value: outbox.data?.backlog, caption: 'events waiting for Kafka' },
  ]
  return (
    <dl className="mt-6 grid grid-cols-2 gap-3 lg:grid-cols-4">
      {tiles.map((tile) => (
        <div key={tile.label} className="rounded-2xl border border-hairline bg-surface px-4 py-3">
          <dt className="text-xs font-semibold tracking-wide text-ink-muted uppercase">{tile.label}</dt>
          <dd className="mt-1 text-3xl font-semibold">
            {tile.value === undefined || stock.data === undefined ? <Skeleton className="h-9 w-16" /> : compact(tile.value)}
          </dd>
          <p className="mt-0.5 text-xs text-ink-secondary">{tile.caption}</p>
        </div>
      ))}
    </dl>
  )
}

function StockView() {
  const { data, error, refetch } = useOpsStockQuery({ limit: 500 }, polling)
  const [adjusting, setAdjusting] = useState<OpsStock | undefined>(undefined)
  const problem = problemOf(error)

  if (problem !== undefined && data === undefined) {
    return (
      <ErrorNotice
        problem={problem}
        onRetry={() => {
          void refetch()
        }}
      />
    )
  }
  if (data === undefined) {
    return <Skeleton className="h-96 rounded-2xl" />
  }
  if (data.items.length === 0) {
    return <EmptyState title="Nothing stocked yet">Adjust a game&apos;s stock to create its first row.</EmptyState>
  }
  return (
    <div className="overflow-hidden rounded-2xl border border-hairline bg-surface">
      <div className="flex items-center justify-between border-b border-hairline px-4 py-3">
        <p className="text-sm font-semibold">{data.items.length} games</p>
        <StockLegend />
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-ink-muted uppercase">
            <tr className="border-b border-hairline">
              <th scope="col" className="px-4 py-2.5 font-semibold">Game</th>
              <th scope="col" className="px-4 py-2.5 text-right font-semibold">On hand</th>
              <th scope="col" className="px-4 py-2.5 text-right font-semibold">Reserved</th>
              <th scope="col" className="px-4 py-2.5 text-right font-semibold">Available</th>
              <th scope="col" className="w-48 px-4 py-2.5 font-semibold">Split</th>
              <th scope="col" className="px-4 py-2.5"><span className="sr-only">Actions</span></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-hairline">
            {data.items.map((stock) => (
              <tr key={stock.sku} className="hover:bg-sunken/50">
                <td className="px-4 py-3">
                  <p className="font-medium">{stock.title ?? <span className="text-ink-muted italic">not in the catalogue</span>}</p>
                  <p className="font-mono text-xs text-ink-muted">{stock.sku}</p>
                </td>
                <td className="numeric px-4 py-3 text-right">{stock.onHand}</td>
                <td className="numeric px-4 py-3 text-right">{stock.reserved}</td>
                <td className={`numeric px-4 py-3 text-right font-semibold ${stock.available <= 0 ? 'text-critical-ink' : ''}`}>
                  {stock.available}
                </td>
                <td className="px-4 py-3">
                  <StockMeter stock={stock} />
                </td>
                <td className="px-4 py-3 text-right">
                  <button
                    type="button"
                    onClick={() => {
                      setAdjusting(stock)
                    }}
                    className={button('secondary', 'sm')}
                  >
                    Adjust
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {adjusting !== undefined && (
        <AdjustDialog
          stock={adjusting}
          onClose={() => {
            setAdjusting(undefined)
          }}
        />
      )}
    </div>
  )
}

function ReservationsView() {
  const [state, setState] = useState<ReservationState | ''>('')
  const { data, error, refetch } = useOpsReservationsQuery(state === '' ? { limit: 100 } : { state, limit: 100 }, polling)
  const problem = problemOf(error)
  return (
    <div>
      <label className="inline-flex items-center gap-2 text-sm text-ink-secondary">
        Recorded state
        <select
          value={state}
          onChange={(event) => {
            setState(event.target.value as ReservationState | '')
          }}
          className="h-9 rounded-lg border border-line-strong bg-surface px-2 text-sm text-ink"
        >
          <option value="">All</option>
          <option value="HELD">Held</option>
          <option value="COMMITTED">Committed</option>
          <option value="RELEASED">Released</option>
          <option value="EXPIRED">Expired</option>
        </select>
      </label>
      <div className="mt-4">
        {problem !== undefined && data === undefined ? (
          <ErrorNotice
            problem={problem}
            onRetry={() => {
              void refetch()
            }}
          />
        ) : data === undefined ? (
          <Skeleton className="h-80 rounded-2xl" />
        ) : data.items.length === 0 ? (
          <EmptyState title="No reservations">Holds appear here as customers place orders.</EmptyState>
        ) : (
          <div className="overflow-x-auto rounded-2xl border border-hairline bg-surface">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-ink-muted uppercase">
                <tr className="border-b border-hairline">
                  <th scope="col" className="px-4 py-2.5 font-semibold">Reservation</th>
                  <th scope="col" className="px-4 py-2.5 font-semibold">Holds</th>
                  <th scope="col" className="px-4 py-2.5 font-semibold">State</th>
                  <th scope="col" className="px-4 py-2.5 font-semibold">Taken</th>
                  <th scope="col" className="px-4 py-2.5 font-semibold">Deadline</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-hairline">
                {data.items.map((reservation) => (
                  <tr key={reservation.id}>
                    <td className="px-4 py-3 font-mono text-xs">{reservation.id.slice(0, 13)}</td>
                    <td className="px-4 py-3">
                      {reservation.lines.map((line) => (
                        <span key={line.sku} className="mr-2 inline-block font-mono text-xs">
                          {line.sku}×{line.quantity}
                        </span>
                      ))}
                    </td>
                    <td className="px-4 py-3">
                      <StateChip state={reservation.state} effectiveState={reservation.effectiveState} />
                    </td>
                    <td className="px-4 py-3 text-ink-secondary" title={dateTime(reservation.createdAt)}>
                      {ago(reservation.createdAt)}
                    </td>
                    <td className="px-4 py-3">
                      {reservation.effectiveState === 'HELD' ? (
                        <Countdown deadline={reservation.expiresAt} />
                      ) : (
                        <span className="text-ink-muted">{dateTime(reservation.expiresAt)}</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}

function OutboxView() {
  const { data, error, refetch } = useOpsOutboxQuery({ limit: 50 }, polling)
  const problem = problemOf(error)
  if (problem !== undefined && data === undefined) {
    return (
      <ErrorNotice
        problem={problem}
        onRetry={() => {
          void refetch()
        }}
      />
    )
  }
  if (data === undefined) {
    return <Skeleton className="h-64 rounded-2xl" />
  }
  return (
    <div>
      <p className="text-sm text-ink-secondary">
        Events are written to the outbox in the same transaction as the change they describe, then published to
        Kafka. A backlog that stays above zero means the publisher is behind or the broker is down.
      </p>
      {data.items.length === 0 ? (
        <div className="mt-4">
          <EmptyState icon="check" title="All caught up">
            Every event the ledger has written has been published.
          </EmptyState>
        </div>
      ) : (
        <ul className="mt-4 divide-y divide-hairline rounded-2xl border border-hairline bg-surface">
          {data.items.map((entry) => (
            <li key={entry.sequence} className="px-4 py-3">
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <span className="font-mono text-xs font-semibold">#{entry.sequence}</span>
                <span className="text-xs text-ink-muted">{ago(entry.recordedAt)}</span>
              </div>
              <p className="mt-1 truncate font-mono text-xs text-ink-secondary" title={entry.payload}>
                {entry.payload}
              </p>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

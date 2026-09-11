import { useCallback, useState } from 'react'
import { useTill } from '../api/context'
import { TillError, type ReservationState } from '../api/client'
import { usePoll } from '../components/usePoll'
import { ErrorNotice } from '../components/ErrorNotice'
import { StateChip } from '../components/StateChip'
import { StatTiles, type Stat } from './StatTiles'
import { compact } from '../format'
import { StockLegend, StockMeter } from './StockMeter'

const REFRESH_MS = 2000
const STATES: readonly ReservationState[] = ['HELD', 'COMMITTED', 'RELEASED', 'EXPIRED']

/**
 * What the ledger holds, refreshed while you watch it.
 *
 * Three panels and one idea: every number here comes from an endpoint that is bounded and indexed.
 * There is no "load everything" anywhere, because a console that works on a demo database and falls
 * over on a real one is a console that will be opened exactly once.
 */
export function OpsPage() {
  const { client } = useTill()
  const [live, setLive] = useState(true)
  const [state, setState] = useState<ReservationState | 'ALL'>('ALL')

  const readStock = useCallback(() => client.listStock(50), [client])
  const readReservations = useCallback(
    () => client.listReservations(state === 'ALL' ? undefined : state, 50),
    [client, state],
  )
  const readOutbox = useCallback(() => client.outbox(10), [client])

  const stock = usePoll(readStock, REFRESH_MS, live)
  const reservations = usePoll(readReservations, REFRESH_MS, live)
  const outbox = usePoll(readOutbox, REFRESH_MS, live)

  const items = stock.data?.items ?? []
  const stats: Stat[] = [
    { label: 'SKUs', value: items.length, caption: 'with a stock row' },
    {
      label: 'On hand',
      value: items.reduce((sum, item) => sum + item.onHand, 0),
      caption: 'units in the building',
    },
    {
      label: 'Reserved',
      value: items.reduce((sum, item) => sum + item.reserved, 0),
      caption: 'held by open reservations',
    },
    {
      label: 'Outbox',
      value: outbox.data?.backlog ?? 0,
      caption: 'events waiting to publish',
    },
  ]

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">Ledger</h1>
          <p className="text-sm text-ink-secondary">
            Refreshed every {REFRESH_MS / 1000}s. Every listing is bounded and indexed.
          </p>
        </div>
        <button
          type="button"
          onClick={() => {
            setLive((current) => !current)
          }}
          aria-pressed={live}
          className="rounded-md border border-line-strong px-3 py-1.5 text-sm font-medium hover:bg-sunken"
        >
          {live ? 'Pause' : 'Resume'}
        </button>
      </header>

      <StatTiles stats={stats} />

      <section className="rounded-lg border border-hairline bg-surface">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-hairline px-4 py-3">
          <h2 className="font-medium">Stock</h2>
          <StockLegend />
        </div>
        <ErrorNoticeRow error={stock.error} onRetry={stock.refresh} />
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs tracking-wide text-ink-muted uppercase">
              <th scope="col" className="px-4 py-2 font-medium">SKU</th>
              <th scope="col" className="w-2/5 px-4 py-2 font-medium">
                Split of on hand
              </th>
              <th scope="col" className="px-4 py-2 text-right font-medium">Available</th>
              <th scope="col" className="px-4 py-2 text-right font-medium">Reserved</th>
              <th scope="col" className="px-4 py-2 text-right font-medium">On hand</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.sku} className="border-t border-hairline">
                <td className="px-4 py-2 font-medium text-ink">{item.sku}</td>
                <td className="px-4 py-2">
                  <StockMeter stock={item} />
                </td>
                <td className="numeric px-4 py-2 text-right">{compact(item.available)}</td>
                <td className="numeric px-4 py-2 text-right">{compact(item.reserved)}</td>
                <td className="numeric px-4 py-2 text-right text-ink-secondary">
                  {compact(item.onHand)}
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr className="border-t border-hairline">
                <td colSpan={5} className="px-4 py-6 text-center text-ink-secondary">
                  Nothing stocked yet. <code>tillctl adjust widget 100</code>, or the shop&rsquo;s
                  restock button.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </section>

      <section className="rounded-lg border border-hairline bg-surface">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-hairline px-4 py-3">
          <h2 className="font-medium">Reservations</h2>
          <div className="flex flex-wrap gap-1">
            {(['ALL', ...STATES] as const).map((option) => (
              <button
                key={option}
                type="button"
                onClick={() => {
                  setState(option)
                }}
                aria-pressed={state === option}
                className={`rounded-md px-2 py-1 text-xs font-medium ${
                  state === option ? 'bg-ink text-surface' : 'text-ink-secondary hover:bg-sunken'
                }`}
              >
                {option}
              </button>
            ))}
          </div>
        </div>
        <ErrorNoticeRow error={reservations.error} onRetry={reservations.refresh} />
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs tracking-wide text-ink-muted uppercase">
              <th scope="col" className="px-4 py-2 font-medium">Reservation</th>
              <th scope="col" className="px-4 py-2 font-medium">State</th>
              <th scope="col" className="px-4 py-2 font-medium">Holds</th>
              <th scope="col" className="px-4 py-2 font-medium">Expires</th>
            </tr>
          </thead>
          <tbody>
            {(reservations.data?.items ?? []).map((reservation) => (
              <tr key={reservation.id} className="border-t border-hairline">
                <td className="numeric px-4 py-2 text-xs text-ink-secondary">
                  {reservation.id.slice(0, 8)}
                </td>
                <td className="px-4 py-2">
                  <StateChip
                    state={reservation.state}
                    effectiveState={reservation.effectiveState}
                  />
                </td>
                <td className="numeric px-4 py-2">
                  {reservation.lines.map((line) => `${line.sku}×${String(line.quantity)}`).join('  ')}
                </td>
                <td className="numeric px-4 py-2 text-xs text-ink-secondary">
                  {new Date(reservation.expiresAt).toLocaleTimeString()}
                </td>
              </tr>
            ))}
            {(reservations.data?.items ?? []).length === 0 && (
              <tr className="border-t border-hairline">
                <td colSpan={4} className="px-4 py-6 text-center text-ink-secondary">
                  No reservations {state === 'ALL' ? 'yet' : `in ${state}`}.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </section>

      <section className="rounded-lg border border-hairline bg-surface">
        <div className="flex items-center justify-between border-b border-hairline px-4 py-3">
          <h2 className="font-medium">Outbox</h2>
          <span className="numeric text-sm text-ink-secondary">
            {compact(outbox.data?.backlog ?? 0)} waiting
          </span>
        </div>
        {outbox.error instanceof TillError && outbox.error.status === 403 ? (
          <p className="px-4 py-6 text-sm text-ink-secondary">
            The outbox needs the admin token. The backlog is not a secret, but the payloads are the
            whole history of what moved and when, which a checkout service has no reason to read.
          </p>
        ) : (
          <>
            <ErrorNoticeRow error={outbox.error} onRetry={outbox.refresh} />
            <ul className="divide-y divide-hairline">
              {(outbox.data?.items ?? []).map((entry) => (
                <li key={entry.sequence} className="px-4 py-2 text-sm">
                  <span className="numeric text-ink-muted">{entry.sequence}</span>{' '}
                  <span className="font-medium text-ink">{entry.dedupeKey}</span>
                  <p className="numeric mt-0.5 truncate text-xs text-ink-secondary">
                    {entry.payload}
                  </p>
                </li>
              ))}
              {(outbox.data?.items ?? []).length === 0 && (
                <li className="px-4 py-6 text-center text-sm text-ink-secondary">
                  Nothing waiting. The publisher has delivered everything written so far.
                </li>
              )}
            </ul>
          </>
        )}
      </section>
    </div>
  )
}

function ErrorNoticeRow({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  if (error === undefined) {
    return null
  }
  return (
    <div className="px-4 py-3">
      <ErrorNotice error={error} onRetry={onRetry} />
    </div>
  )
}

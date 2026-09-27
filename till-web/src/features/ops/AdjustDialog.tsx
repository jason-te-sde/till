import { useState } from 'react'
import { newAttemptKey } from '../../api/idempotency'
import { problemOf } from '../../api/problem'
import { useAdjustStockMutation } from '../../api/storeApi'
import type { OpsStock } from '../../api/types'
import { useAppDispatch } from '../../app/hooks'
import { toastShown } from '../../app/uiSlice'
import { button } from '../../components/button'
import { Modal } from '../../components/Modal'
import { ErrorNotice } from '../../components/States'

const QUICK = [-10, -1, 1, 10, 50]

/**
 * Adding or removing stock by hand — a delivery, a write-off, a correction.
 *
 * The dialog is one attempt: its key is made when it opens and reused by every submit, so a retry after
 * a timeout adjusts once. Opening it again is a new adjustment with a new key.
 */
export function AdjustDialog({ stock, onClose }: { stock: OpsStock; onClose: () => void }) {
  const [delta, setDelta] = useState(10)
  const [key] = useState(() => newAttemptKey('adjust'))
  const [adjust, { isLoading, error }] = useAdjustStockMutation()
  const dispatch = useAppDispatch()
  const problem = problemOf(error)
  const after = stock.onHand + delta
  const invalid = delta === 0 || !Number.isInteger(delta) || after < stock.reserved

  async function submit() {
    try {
      const level = await adjust({ sku: stock.sku, delta, key }).unwrap()
      dispatch(
        toastShown({
          tone: 'success',
          title: `${stock.title ?? stock.sku}: ${String(level.onHand)} on hand`,
          message: `${delta > 0 ? 'Added' : 'Removed'} ${String(Math.abs(delta))}.`,
        }),
      )
      onClose()
    } catch {
      // Shown in the dialog.
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={`Adjust ${stock.title ?? stock.sku}`}
      footer={
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className={button('ghost')}>
            Cancel
          </button>
          <button
            type="button"
            disabled={invalid || isLoading}
            onClick={() => {
              void submit()
            }}
            className={button('primary')}
          >
            {isLoading ? 'Saving…' : delta >= 0 ? `Add ${String(Math.abs(delta))}` : `Remove ${String(Math.abs(delta))}`}
          </button>
        </div>
      }
    >
      <dl className="grid grid-cols-3 gap-3 text-center">
        {[
          ['On hand', stock.onHand],
          ['Reserved', stock.reserved],
          ['Available', stock.available],
        ].map(([label, value]) => (
          <div key={label} className="rounded-xl bg-sunken p-3">
            <dt className="text-xs text-ink-muted">{label}</dt>
            <dd className="numeric mt-0.5 text-xl font-semibold">{value}</dd>
          </div>
        ))}
      </dl>

      <label className="mt-5 block text-sm font-medium" htmlFor="adjust-delta">
        Change on-hand by
      </label>
      <input
        id="adjust-delta"
        type="number"
        inputMode="numeric"
        step={1}
        value={Number.isNaN(delta) ? '' : delta}
        onChange={(event) => {
          setDelta(event.target.valueAsNumber)
        }}
        className="numeric mt-1.5 h-11 w-full rounded-lg border border-line-strong bg-surface px-3 text-lg font-semibold"
      />
      <div className="mt-2 flex flex-wrap gap-1.5">
        {QUICK.map((step) => (
          <button
            key={step}
            type="button"
            onClick={() => {
              setDelta(step)
            }}
            className="rounded-md border border-hairline px-2.5 py-1 text-xs font-semibold text-ink-secondary hover:bg-sunken hover:text-ink"
          >
            {step > 0 ? `+${String(step)}` : step}
          </button>
        ))}
      </div>
      <p className="mt-4 text-sm text-ink-secondary">
        {after < stock.reserved
          ? `That would leave ${String(after)} on hand with ${String(stock.reserved)} promised to open holds — the ledger will refuse it.`
          : `On hand becomes ${String(after)}; ${String(after - stock.reserved)} available.`}
      </p>
      {problem !== undefined && (
        <div className="mt-4">
          <ErrorNotice
            compact
            problem={problem}
            onRetry={() => {
              void submit()
            }}
          />
        </div>
      )}
    </Modal>
  )
}

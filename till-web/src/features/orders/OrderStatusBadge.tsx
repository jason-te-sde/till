import type { OrderStatus } from '../../api/types'

const LOOK: Record<OrderStatus, { label: string; dot: string; text: string }> = {
  PENDING: { label: 'Awaiting payment', dot: 'bg-warning', text: 'text-warning-ink' },
  PAID: { label: 'Paid', dot: 'bg-good', text: 'text-good-ink' },
  CANCELLED: { label: 'Cancelled', dot: 'bg-ink-muted', text: 'text-ink-secondary' },
  EXPIRED: { label: 'Expired', dot: 'bg-ink-muted', text: 'text-ink-secondary' },
}

/**
 * An order's status as a word first and a colour second — always beside each other, so it reads the
 * same in greyscale and to a screen reader.
 */
export function OrderStatusBadge({ status }: { status: OrderStatus }) {
  const look = LOOK[status]
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full border border-hairline bg-surface px-2.5 py-0.5 text-xs font-semibold ${look.text}`}>
      <span aria-hidden="true" className={`size-1.5 rounded-full ${look.dot}`} />
      {look.label}
    </span>
  )
}

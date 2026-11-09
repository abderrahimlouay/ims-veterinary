import { useId } from 'react'
import { Link } from 'react-router'

export interface BarItem {
  /** What the bar measures: a product, a category, a customer… */
  label: string
  /** The server's decimal string; converted to a number only to size the bar (BR-SALE-008). */
  value: string
  /** The amount as the screen shows it. */
  display: string
  /** Opens the item's page when given. */
  to?: string
}

/**
 * A ranked list with one horizontal bar per item, the longest the largest (dashboard, ADR-176): the label and the amount
 * are text, the bar an SVG coloured by tokens only (both themes, the strict CSP: no inline style), and the list runs
 * right to left in Arabic by itself. A screen aid: the figures are the text beside each bar.
 */
export function BarListChart({ title, items }: { title: string; items: BarItem[] }) {
  const id = useId()
  const high = Math.max(0, ...items.map((item) => Number(item.value)))
  return (
    <figure className="flex flex-col gap-2">
      <figcaption id={`${id}-title`} className="text-sm font-medium">
        {title}
      </figcaption>
      <ol aria-labelledby={`${id}-title`} className="flex flex-col gap-1.5">
        {items.map((item, i) => {
          const share = high > 0 ? Math.max(0, Number(item.value)) / high : 0
          return (
            <li key={i} className="grid grid-cols-[minmax(0,1fr)_auto] gap-x-3 text-sm">
              <span className="truncate" title={item.label}>
                {item.to ? (
                  <Link to={item.to} className="underline underline-offset-4 decoration-line hover:text-brand">
                    {item.label}
                  </Link>
                ) : (
                  item.label
                )}
              </span>
              <span className="text-end tabular-nums">{item.display}</span>
              <svg aria-hidden="true" className="col-span-2 h-2 w-full rtl:-scale-x-100" preserveAspectRatio="none">
                <rect width="100%" height="100%" rx="4" className="fill-line" />
                <rect width={`${(share * 100).toFixed(2)}%`} height="100%" rx="4" className="fill-brand" />
              </svg>
            </li>
          )
        })}
      </ol>
    </figure>
  )
}

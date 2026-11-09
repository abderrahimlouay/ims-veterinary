import { useId } from 'react'
import { useTranslation } from 'react-i18next'

export interface ChartPoint {
  /** The period as the table shows it ("11/2026", "06/11/2026"). */
  label: string
  /** The server's decimal string (BR-SALE-008); converted to a number only to size the column. */
  value: string
  /** The amount as the table shows it. */
  display: string
}

const WIDTH = 720
const HEIGHT = 220
const TOP = 12
const BOTTOM = 28
const MAX_LABELS = 12

/**
 * A column per period (ADR-164): our own SVG, coloured by tokens only (both themes), under the strict CSP, mirrored in
 * Arabic. Negative columns (a period with more credit notes than sales) go below the zero line in `danger`. A screen
 * aid only: the table under it carries the figures, the PDF and CSV have no chart.
 */
export function ColumnChart({ title, points }: { title: string; points: ChartPoint[] }) {
  const { t, i18n } = useTranslation()
  const id = useId()
  const rtl = i18n.dir(i18n.language) === 'rtl'
  const values = points.map((p) => Number(p.value))
  const high = Math.max(0, ...values)
  const low = Math.min(0, ...values)
  const span = high - low || 1
  const plot = HEIGHT - TOP - BOTTOM
  const y = (v: number) => TOP + ((high - v) / span) * plot
  const zero = y(0)
  const slot = WIDTH / Math.max(points.length, 1)
  const bar = Math.max(1, Math.min(48, slot * 0.7))
  const step = Math.ceil(points.length / MAX_LABELS)
  const x = (i: number) => (rtl ? WIDTH - (i + 1) * slot : i * slot)
  const peak = points[values.indexOf(high)]
  const trough = points[values.indexOf(low)]

  return (
    <figure className="flex flex-col gap-1 max-w-3xl">
      <figcaption id={`${id}-title`} className="text-sm font-medium">
        {title}
      </figcaption>
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-labelledby={`${id}-title`} className="w-full h-auto overflow-visible">
        {points.map((p, i) => {
          const v = values[i]
          const top = v >= 0 ? y(v) : zero
          return (
            <rect
              key={i}
              x={x(i) + (slot - bar) / 2}
              y={top}
              width={bar}
              height={Math.max(v === 0 ? 0 : 1, Math.abs(y(v) - zero))}
              className={v < 0 ? 'fill-danger' : 'fill-brand'}
            >
              <title>{`${p.label} : ${p.display}`}</title>
            </rect>
          )
        })}
        <line x1={0} x2={WIDTH} y1={zero} y2={zero} className="stroke-control" strokeWidth={1} />
        {points.map((p, i) =>
          i % step === 0 ? (
            <text key={i} x={x(i) + slot / 2} y={HEIGHT - 8} textAnchor="middle" className="fill-mute text-[11px]">
              {p.label}
            </text>
          ) : null,
        )}
      </svg>
      {peak !== undefined && high > 0 && (
        <p className="text-xs text-mute">
          {t('chart.highest', { period: peak.label, amount: peak.display })}
          {trough !== undefined && low < 0 && ` · ${t('chart.lowest', { period: trough.label, amount: trough.display })}`}
        </p>
      )}
    </figure>
  )
}

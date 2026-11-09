import { useId, useState, type KeyboardEvent, type MouseEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { STROKE, WASH, FILL, type ChartTone } from './chartColours'
import type { ChartPoint } from './ColumnChart'

const WIDTH = 720
const HEIGHT = 220
const TOP = 28
const BOTTOM = 28
const SIDE = 8
const MAX_LABELS = 12
const TIP_HEIGHT = 20

/**
 * A trend over time (ADR-177): one series as a 2 px line over a light wash of its colour, from zero up; our own SVG,
 * coloured by tokens only (both themes, the strict CSP), mirrored in Arabic. Hovering, or the arrow keys once focused,
 * moves a marker that names the period and its amount. A screen aid: the caption gives the highest period.
 */
export function AreaChart({ title, points, tone = 'chart-1' }: { title: string; points: ChartPoint[]; tone?: ChartTone }) {
  const { t, i18n } = useTranslation()
  const id = useId()
  const rtl = i18n.dir(i18n.language) === 'rtl'
  const [active, setActive] = useState<number | null>(null)
  const values = points.map((p) => Math.max(0, Number(p.value)))
  const high = Math.max(0, ...values) || 1
  const plot = HEIGHT - TOP - BOTTOM
  const span = WIDTH - 2 * SIDE
  const x = (i: number) => {
    const at = SIDE + (points.length <= 1 ? span / 2 : (i * span) / (points.length - 1))
    return rtl ? WIDTH - at : at
  }
  const y = (v: number) => TOP + plot - (v / high) * plot
  const base = TOP + plot
  const line = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ')
  const area = points.length === 0 ? '' : `${line} L${x(points.length - 1).toFixed(1)},${base} L${x(0).toFixed(1)},${base} Z`
  const step = Math.ceil(points.length / MAX_LABELS)
  const peakIndex = values.indexOf(Math.max(...values))
  const peak = points[peakIndex]

  const nearest = (event: MouseEvent<SVGSVGElement>) => {
    const box = event.currentTarget.getBoundingClientRect()
    const at = ((event.clientX - box.left) / box.width) * WIDTH
    const along = rtl ? WIDTH - at : at
    const index = points.length <= 1 ? 0 : Math.round(((along - SIDE) / span) * (points.length - 1))
    setActive(Math.min(points.length - 1, Math.max(0, index)))
  }
  const keys = (event: KeyboardEvent<SVGSVGElement>) => {
    const forward = rtl ? 'ArrowLeft' : 'ArrowRight'
    const back = rtl ? 'ArrowRight' : 'ArrowLeft'
    if (event.key !== forward && event.key !== back) {
      return
    }
    event.preventDefault()
    const from = active ?? (event.key === forward ? -1 : points.length)
    setActive(Math.min(points.length - 1, Math.max(0, from + (event.key === forward ? 1 : -1))))
  }

  const shown = active === null ? null : points[active]
  const tipText = shown ? `${shown.label} : ${shown.display}` : ''
  const tipWidth = tipText.length * 6.6 + 14
  const tipX = active === null ? 0 : Math.min(WIDTH - tipWidth, Math.max(0, x(active) - tipWidth / 2))

  return (
    <figure className="flex flex-col gap-1 max-w-3xl">
      <figcaption id={`${id}-title`} className="text-sm font-medium">
        {title}
      </figcaption>
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-labelledby={`${id}-title`}
        tabIndex={0}
        onMouseMove={nearest}
        onMouseLeave={() => setActive(null)}
        onKeyDown={keys}
        onBlur={() => setActive(null)}
        className="w-full h-auto overflow-visible focus-visible:outline-2 focus-visible:outline-brand rounded-ctl"
      >
        <line x1={0} x2={WIDTH} y1={base} y2={base} className="stroke-control" strokeWidth={1} />
        <path d={area} className={WASH[tone]} />
        <path d={line} fill="none" className={STROKE[tone]} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
        {points.map((p, i) =>
          i % step === 0 ? (
            <text key={i} x={x(i)} y={HEIGHT - 8} textAnchor="middle" className="fill-mute text-[11px]">
              {p.label}
            </text>
          ) : null,
        )}
        {active !== null && shown && (
          <g aria-hidden="true">
            <line x1={x(active)} x2={x(active)} y1={TOP} y2={base} className="stroke-control" strokeWidth={1} strokeDasharray="3 3" />
            <circle cx={x(active)} cy={y(values[active] ?? 0)} r={4.5} className={`${FILL[tone]} stroke-surface`} strokeWidth={2} />
            <rect x={tipX} y={2} width={tipWidth} height={TIP_HEIGHT} rx={4} className="fill-surface stroke-line" strokeWidth={1} />
            <text x={tipX + tipWidth / 2} y={2 + TIP_HEIGHT / 2 + 4} textAnchor="middle" className="fill-ink text-[11px]">
              {tipText}
            </text>
          </g>
        )}
      </svg>
      {peak !== undefined && values[peakIndex] > 0 && (
        <p className="text-xs text-mute">{t('chart.highest', { period: peak.label, amount: peak.display })}</p>
      )}
      <p role="status" className="sr-only">
        {tipText}
      </p>
    </figure>
  )
}

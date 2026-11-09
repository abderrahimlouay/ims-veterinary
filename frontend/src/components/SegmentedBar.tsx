import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { formatRate } from '../lib/format'
import { FILL, SWATCH, type ChartTone } from './chartColours'

export interface Segment {
  label: string
  /** The server's decimal string; a number only to size the block (BR-SALE-008). */
  value: string
  display: string
  tone: ChartTone
}

const WIDTH = 720
const HEIGHT = 28
const GAP = 2

/**
 * One whole split into its parts (ADR-177): a tall bar with a block per part, 2 px of surface between blocks, and a
 * legend naming each part with its amount and share, so colour never carries the identity alone. Our own SVG coloured
 * by tokens only (both themes, the strict CSP), mirrored in Arabic. At most six parts: the caller folds the rest into
 * "other".
 */
export function SegmentedBar({ title, segments }: { title: string; segments: Segment[] }) {
  const { t } = useTranslation()
  const id = useId()
  const values = segments.map((s) => Math.max(0, Number(s.value)))
  const total = values.reduce((sum, v) => sum + v, 0)
  const drawn = segments.filter((_, i) => values[i] > 0).length
  const room = WIDTH - GAP * Math.max(0, drawn - 1)
  const widths = values.map((v) => (total > 0 ? (v / total) * room : 0))
  // Each block starts after the drawn ones before it and their gaps.
  const blocks = segments.map((segment, i) => {
    const before = widths.slice(0, i).filter((w) => w > 0)
    const x = before.reduce((sum, w) => sum + w, 0) + GAP * before.length
    return { segment, x, width: widths[i] }
  })
  const share = (i: number) => (total > 0 ? formatRate(((values[i] / total) * 100).toFixed(1)) : '0')

  return (
    <figure className="flex flex-col gap-2">
      <figcaption id={`${id}-title`} className="text-sm font-medium">
        {title}
      </figcaption>
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-labelledby={`${id}-title`} preserveAspectRatio="none" className="w-full h-7 rtl:-scale-x-100">
        {blocks.map(({ segment, x, width }, i) =>
          width > 0 ? (
            <rect key={i} x={x} y={0} width={width} height={HEIGHT} rx={3} className={FILL[segment.tone]}>
              <title>{`${segment.label} : ${segment.display} (${share(i)} %)`}</title>
            </rect>
          ) : null,
        )}
      </svg>
      <ul className="flex flex-col gap-1 text-sm" aria-label={t('chart.legend', { title })}>
        {segments.map((segment, i) => (
          <li key={i} className="grid grid-cols-[auto_minmax(0,1fr)_auto_auto] items-center gap-x-2">
            <span aria-hidden="true" className={`size-3 rounded-sm ${SWATCH[segment.tone]}`} />
            <span className="truncate" title={segment.label}>
              {segment.label}
            </span>
            <span className="text-end tabular-nums">{segment.display}</span>
            <span className="w-14 text-end text-mute tabular-nums">{share(i)} %</span>
          </li>
        ))}
      </ul>
    </figure>
  )
}

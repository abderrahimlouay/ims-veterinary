/**
 * The chart colours (ADR-177): the categorical slots `chart-1…8` in their validated order, `other` for a folded tail,
 * `brand` for the reports' charts, and the lateness grades `status-*`. Written out in full so Tailwind generates every
 * class; components never hold a colour of their own (ADR-069).
 */
export type ChartTone =
  | 'brand'
  | 'chart-1'
  | 'chart-2'
  | 'chart-3'
  | 'chart-4'
  | 'chart-5'
  | 'chart-6'
  | 'chart-7'
  | 'chart-8'
  | 'other'
  | 'status-good'
  | 'status-warning'
  | 'status-serious'
  | 'status-critical'

export const FILL: Record<ChartTone, string> = {
  brand: 'fill-brand',
  'chart-1': 'fill-chart-1',
  'chart-2': 'fill-chart-2',
  'chart-3': 'fill-chart-3',
  'chart-4': 'fill-chart-4',
  'chart-5': 'fill-chart-5',
  'chart-6': 'fill-chart-6',
  'chart-7': 'fill-chart-7',
  'chart-8': 'fill-chart-8',
  other: 'fill-control',
  'status-good': 'fill-status-good',
  'status-warning': 'fill-status-warning',
  'status-serious': 'fill-status-serious',
  'status-critical': 'fill-status-critical',
}

export const STROKE: Record<ChartTone, string> = {
  brand: 'stroke-brand',
  'chart-1': 'stroke-chart-1',
  'chart-2': 'stroke-chart-2',
  'chart-3': 'stroke-chart-3',
  'chart-4': 'stroke-chart-4',
  'chart-5': 'stroke-chart-5',
  'chart-6': 'stroke-chart-6',
  'chart-7': 'stroke-chart-7',
  'chart-8': 'stroke-chart-8',
  other: 'stroke-control',
  'status-good': 'stroke-status-good',
  'status-warning': 'stroke-status-warning',
  'status-serious': 'stroke-status-serious',
  'status-critical': 'stroke-status-critical',
}

/** A light wash of the slot under a line (an area chart's fill). */
export const WASH: Record<ChartTone, string> = {
  brand: 'fill-brand/15',
  'chart-1': 'fill-chart-1/15',
  'chart-2': 'fill-chart-2/15',
  'chart-3': 'fill-chart-3/15',
  'chart-4': 'fill-chart-4/15',
  'chart-5': 'fill-chart-5/15',
  'chart-6': 'fill-chart-6/15',
  'chart-7': 'fill-chart-7/15',
  'chart-8': 'fill-chart-8/15',
  other: 'fill-control/15',
  'status-good': 'fill-status-good/15',
  'status-warning': 'fill-status-warning/15',
  'status-serious': 'fill-status-serious/15',
  'status-critical': 'fill-status-critical/15',
}

/** The background of a legend swatch (an HTML square, not SVG). */
export const SWATCH: Record<ChartTone, string> = {
  brand: 'bg-brand',
  'chart-1': 'bg-chart-1',
  'chart-2': 'bg-chart-2',
  'chart-3': 'bg-chart-3',
  'chart-4': 'bg-chart-4',
  'chart-5': 'bg-chart-5',
  'chart-6': 'bg-chart-6',
  'chart-7': 'bg-chart-7',
  'chart-8': 'bg-chart-8',
  other: 'bg-control',
  'status-good': 'bg-status-good',
  'status-warning': 'bg-status-warning',
  'status-serious': 'bg-status-serious',
  'status-critical': 'bg-status-critical',
}

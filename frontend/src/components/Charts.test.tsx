import { fireEvent, render as rawRender, screen, within } from '@testing-library/react'
import axe from 'axe-core'
import type { ReactNode } from 'react'
import { I18nextProvider } from 'react-i18next'
import { describe, expect, it } from 'vitest'
import { AreaChart } from './AreaChart'
import { BarListChart } from './BarListChart'
import { ColumnChart } from './ColumnChart'
import { SegmentedBar } from './SegmentedBar'
import { i18n } from '../lib/i18n'

const render = (ui: ReactNode) => rawRender(<I18nextProvider i18n={i18n}>{ui}</I18nextProvider>)

const points = [
  { label: '01/11', value: '10.00', display: '10,00 DA' },
  { label: '02/11', value: '30.00', display: '30,00 DA' },
  { label: '03/11', value: '0.00', display: '0,00 DA' },
]

describe('chart types and colours (ADR-177)', () => {
  it('draws a trend as a line over a wash of its slot, and names a period on hover or with the arrow keys', () => {
    render(<AreaChart title="Ventes par jour" points={points} tone="chart-1" />)
    const chart = screen.getByRole('img', { name: 'Ventes par jour' })
    expect(chart.querySelector('path[fill="none"]')).toHaveClass('stroke-chart-1')
    expect(chart.querySelector('path:not([fill])')).toHaveClass('fill-chart-1/15')
    expect(screen.getByText('Plus haut : 02/11, 30,00 DA')).toBeInTheDocument()

    chart.focus()
    fireEvent.keyDown(chart, { key: 'ArrowRight' })
    expect(screen.getByRole('status')).toHaveTextContent('01/11 : 10,00 DA')
    fireEvent.keyDown(chart, { key: 'ArrowRight' })
    expect(screen.getByRole('status')).toHaveTextContent('02/11 : 30,00 DA')
    fireEvent.mouseLeave(chart)
    expect(screen.getByRole('status')).toHaveTextContent('')
  })

  it('colours columns by the chart, or one by one when they grade something', () => {
    render(
      <>
        <ColumnChart title="Par mois" points={points} tone="chart-3" />
        <ColumnChart title="Retard" points={points.map((p, i) => ({ ...p, tone: i === 0 ? 'status-good' : 'status-critical' }))} />
      </>,
    )
    expect(screen.getByRole('img', { name: 'Par mois' }).querySelector('rect')).toHaveClass('fill-chart-3')
    expect([...screen.getByRole('img', { name: 'Retard' }).querySelectorAll('rect')].map((r) => r.getAttribute('class'))).toEqual([
      'fill-status-good', 'fill-status-critical', 'fill-status-critical',
    ])
  })

  it('splits a whole into blocks with a gap between them and a legend naming each part, its amount and share', () => {
    render(
      <SegmentedBar
        title="Par catégorie"
        segments={[
          { label: 'Antibiotiques', value: '75.00', display: '75,00 DA', tone: 'chart-1' },
          { label: 'Autres catégories', value: '25.00', display: '25,00 DA', tone: 'other' },
        ]}
      />,
    )
    const blocks = [...screen.getByRole('img', { name: 'Par catégorie' }).querySelectorAll('rect')]
    expect(blocks.map((b) => b.getAttribute('class'))).toEqual(['fill-chart-1', 'fill-control'])
    expect(Number(blocks[1].getAttribute('x'))).toBeCloseTo(Number(blocks[0].getAttribute('width')) + 2)
    const legend = screen.getByRole('list', { name: 'Légende : Par catégorie' })
    expect(within(legend).getAllByRole('listitem')[0]).toHaveTextContent(/Antibiotiques.*75,00 DA.*75 %/)
  })

  it('draws ranked bars in the list colour', () => {
    render(<BarListChart title="Top" tone="chart-2" items={[{ label: 'A', value: '2', display: '2' }, { label: 'B', value: '1', display: '1' }]} />)
    const bars = screen.getByRole('list', { name: 'Top' }).querySelectorAll('rect.fill-chart-2')
    expect(bars).toHaveLength(2)
    expect(bars[1].getAttribute('width')).toBe('50.00%')
  })

  it('passes the automated accessibility checks', async () => {
    const { container } = render(
      <>
        <AreaChart title="Ventes par jour" points={points} />
        <SegmentedBar title="Par catégorie" segments={[{ label: 'A', value: '1', display: '1', tone: 'chart-1' }]} />
      </>,
    )
    const results = await axe.run(container)
    expect(results.violations.map((v) => v.id)).toEqual([])
  })
})

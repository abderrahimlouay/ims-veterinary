import { act, render, screen } from '@testing-library/react'
import axe from 'axe-core'
import { I18nextProvider } from 'react-i18next'
import { afterEach, describe, expect, it } from 'vitest'
import { i18n } from '../lib/i18n'
import { ColumnChart, type ChartPoint } from './ColumnChart'

const POINTS: ChartPoint[] = [
  { label: '09/2026', value: '1000.00', display: '1 000,00 DA' },
  { label: '10/2026', value: '2500.50', display: '2 500,50 DA' },
  { label: '11/2026', value: '-200.00', display: '-200,00 DA' },
]

function renderChart(points = POINTS) {
  return render(
    <I18nextProvider i18n={i18n}>
      <ColumnChart title="Chiffre d'affaires HT par période" points={points} />
    </I18nextProvider>,
  )
}

const columns = (container: HTMLElement) => Array.from(container.querySelectorAll('rect'))

afterEach(async () => {
  await act(() => i18n.changeLanguage('fr'))
})

describe('ColumnChart (ADR-164)', () => {
  it('is an image named by its title, with a column per period and the amount on hover', () => {
    const { container } = renderChart()
    expect(screen.getByRole('img', { name: "Chiffre d'affaires HT par période" })).toBeInTheDocument()
    expect(columns(container).map((r) => r.querySelector('title')?.textContent)).toEqual([
      '09/2026 : 1 000,00 DA',
      '10/2026 : 2 500,50 DA',
      '11/2026 : -200,00 DA',
    ])
    expect(screen.getByText('11/2026')).toBeInTheDocument()
  })

  it('sizes columns by value, draws a negative period below the zero line in danger, and names the extremes', () => {
    const { container } = renderChart()
    const [sep, oct, nov] = columns(container)
    const height = (r: Element) => Number(r.getAttribute('height'))
    expect(height(oct)).toBeGreaterThan(height(sep))
    expect(height(oct) / height(sep)).toBeCloseTo(2.5005, 2)
    expect(sep.getAttribute('class')).toBe('fill-brand')
    expect(nov.getAttribute('class')).toBe('fill-danger')
    const zero = Number(container.querySelector('line')?.getAttribute('y1'))
    expect(Number(oct.getAttribute('y')) + height(oct)).toBeCloseTo(zero)
    expect(Number(nov.getAttribute('y'))).toBeCloseTo(zero)
    expect(screen.getByText(/Plus haut : 10\/2026, 2 500,50 DA/)).toBeInTheDocument()
    expect(screen.getByText(/Plus bas : 11\/2026, -200,00 DA/)).toBeInTheDocument()
  })

  it('runs right to left in Arabic', async () => {
    const { container } = renderChart()
    const xs = () => columns(container).map((r) => Number(r.getAttribute('x')))
    const ltr = xs()
    expect(ltr[0]).toBeLessThan(ltr[2])
    await act(() => i18n.changeLanguage('ar'))
    const rtl = xs()
    expect(rtl[0]).toBeGreaterThan(rtl[2])
  })

  it('labels at most twelve periods on the axis when there are many days', () => {
    const days = Array.from({ length: 31 }, (_, i) => ({
      label: `${String(i + 1).padStart(2, '0')}/10/2026`,
      value: String(i * 10),
      display: `${i * 10} DA`,
    }))
    const { container } = renderChart(days)
    expect(columns(container)).toHaveLength(31)
    expect(container.querySelectorAll('svg text').length).toBeLessThanOrEqual(12)
  })

  it('uses design tokens only and has no axe violation', async () => {
    const { container } = renderChart()
    expect(container.innerHTML).not.toMatch(/#[0-9a-f]{3,8}\b|rgb\(/i)
    const result = await axe.run(container, { runOnly: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'] })
    expect(result.violations).toEqual([])
  })
})

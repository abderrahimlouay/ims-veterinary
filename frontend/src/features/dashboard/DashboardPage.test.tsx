import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { businessToday } from '../../lib/expiry'
import { aUser, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'
import { addDays } from './periods'

const empty = { uninvoicedDeliveryNotes: { count: 0, totalHt: '0.00' } }

function serveRevenue(seen: URLSearchParams[]) {
  server.use(
    http.get('/api/reports/revenue', ({ request }) => {
      const params = new URL(request.url).searchParams
      seen.push(params)
      const groupBy = params.get('groupBy')
      const today = businessToday()
      if (groupBy === 'day') {
        return HttpResponse.json({ from: params.get('from'), to: today, groupBy, totalHt: '1500.00', ...empty,
          rows: [{ key: addDays(today, -2), code: null, label: null, revenueHt: '1000.00' }, { key: today, code: null, label: null, revenueHt: '500.00' }] })
      }
      if (groupBy === 'month') {
        return HttpResponse.json({ from: params.get('from'), to: today, groupBy, totalHt: '1500.00', ...empty,
          rows: [{ key: today.substring(0, 7), code: null, label: null, revenueHt: '1500.00' }] })
      }
      if (groupBy === 'product') {
        return HttpResponse.json({ from: today, to: today, groupBy, totalHt: '1500.00', ...empty,
          rows: [{ key: '12', code: 'DM-AMX', label: 'Amoxival', revenueHt: '1000.00' }, { key: '13', code: 'DM-ALB', label: 'Albendazole', revenueHt: '500.00' }] })
      }
      return HttpResponse.json({ from: today, to: today, groupBy, totalHt: '1500.00', ...empty,
        rows: [{ key: '3', code: null, label: 'Antibiotiques', revenueHt: '1000.00' }, { key: null, code: null, label: null, revenueHt: '500.00' }] })
    }),
  )
}

const ageing = (notDue: string, overdue: string[], advance: string, balance: string) => ({
  notDue, overdue, advance, balance, uninvoicedDeliveryNotes: '0.00',
})

function serveDebts() {
  server.use(
    http.get('/api/reports/customer-balances', () =>
      HttpResponse.json({ date: '2026-11-09', customerType: null, bucketDays: [30, 60, 90],
        rows: [
          { customerId: 4, name: 'Ferme Benali', phone: null, wilaya: null, ...ageing('2000.00', ['0.00', '1000.00', '0.00', '0.00'], '0.00', '3000.00') },
          { customerId: 5, name: 'Cabinet Vétérinaire Sud', phone: null, wilaya: null, ...ageing('500.00', ['0.00', '0.00', '0.00', '250.00'], '50.00', '700.00') },
        ],
        totals: ageing('2500.00', ['0.00', '1000.00', '0.00', '250.00'], '50.00', '3700.00') }),
    ),
    http.get('/api/reports/supplier-payables', () =>
      HttpResponse.json({ date: '2026-11-09', supplierId: null, totalOpen: '9000.00', totalOverdue: '2000.00', unappliedCreditNotes: '0.00', advances: '0.00', balance: '9000.00',
        rows: [
          { invoiceId: 1, supplierId: 8, supplierName: 'Medivet', invoiceNumber: 'F-1', invoiceDate: '2026-09-01', dueDate: '2026-10-01', daysOverdue: 39, totalTtc: '2000.00', open: '2000.00' },
          { invoiceId: 2, supplierId: 9, supplierName: 'Atlas Vét', invoiceNumber: 'A-7', invoiceDate: '2026-11-01', dueDate: '2026-12-01', daysOverdue: 0, totalTtc: '3000.00', open: '3000.00' },
          { invoiceId: 3, supplierId: 8, supplierName: 'Medivet', invoiceNumber: 'F-2', invoiceDate: '2026-11-02', dueDate: '2026-12-02', daysOverdue: 0, totalTtc: '4000.00', open: '4000.00' },
        ] }),
    ),
  )
}

describe('dashboard charts (ADR-176)', () => {
  it('charts the sales of the last 30 days and 12 months, every period shown, and this month best products and categories', async () => {
    serveMe(aUser({ permissions: ['report.sales'] }))
    const seen: URLSearchParams[] = []
    serveRevenue(seen)
    renderApp('/')

    expect(await screen.findByTestId('sales-30-days')).toHaveTextContent('1 500,00')
    const daily = screen.getByRole('img', { name: 'Ventes par jour, 30 derniers jours' })
    expect(daily.querySelectorAll('rect')).toHaveLength(30)
    expect(screen.getByRole('img', { name: 'Ventes par mois, 12 derniers mois' }).querySelectorAll('rect')).toHaveLength(12)
    const today = businessToday()
    expect(seen.find((p) => p.get('groupBy') === 'day')?.get('from')).toBe(addDays(today, -29))

    const products = await screen.findByRole('list', { name: 'Les 10 produits les plus vendus ce mois' })
    expect(within(products).getByRole('link', { name: 'Amoxival' })).toHaveAttribute('href', '/products/12')
    const categories = screen.getByRole('list', { name: 'Ventes par catégorie ce mois' })
    expect(within(categories).getByText('Antibiotiques')).toBeInTheDocument()
    expect(within(categories).getByText('Sans catégorie')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Dettes' })).not.toBeInTheDocument()
  })

  it('shows what customers owe by lateness with the top debtors, and what the shop owes suppliers', async () => {
    serveMe(aUser({ permissions: ['report.finance'] }))
    serveDebts()
    renderApp('/')

    expect(await screen.findByTestId('debt-customers-total')).toHaveTextContent('3 700,00')
    expect(screen.getByText(/Avances des clients déjà déduites : 50,00/)).toBeInTheDocument()
    const lateness = screen.getByRole('img', { name: 'Par retard de paiement' })
    expect([...lateness.querySelectorAll('text')].map((label) => label.textContent)).toEqual(['Non échu', '1–30 j', '31–60 j', '61–90 j', '> 90 j'])
    const debtors = screen.getByRole('list', { name: 'Les 5 clients qui doivent le plus' })
    expect(within(debtors).getAllByRole('link').map((link) => link.textContent)).toEqual(['Ferme Benali', 'Cabinet Vétérinaire Sud'])

    expect(await screen.findByTestId('debt-suppliers-total')).toHaveTextContent('9 000,00')
    expect(screen.getByText(/Factures non payées : 9 000,00/)).toBeInTheDocument()
    expect(screen.getByText(/dont en retard : 2 000,00/)).toBeInTheDocument()
    const owed = screen.getByRole('list', { name: 'Les 5 fournisseurs à qui la pharmacie doit le plus' })
    expect(within(owed).getAllByRole('listitem')[0]).toHaveTextContent(/Medivet.*6 000,00/)
    expect(within(owed).getByRole('link', { name: 'Medivet' })).toHaveAttribute('href', '/suppliers/8')
    expect(screen.queryByRole('heading', { name: 'Ventes' })).not.toBeInTheDocument()
  })

  it('shows no chart without the report permissions', async () => {
    serveMe(aUser({ permissions: [] }))
    renderApp('/')

    expect(await screen.findByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Ventes' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Dettes' })).not.toBeInTheDocument()
  })
})

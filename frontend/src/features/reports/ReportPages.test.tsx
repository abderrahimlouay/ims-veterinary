import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const REVENUE = {
  from: '2026-10-01',
  to: '2026-10-20',
  groupBy: 'category',
  rows: [
    { key: '3', code: null, label: 'Antiparasitaires', revenueHt: '2000.00' },
    { key: null, code: null, label: null, revenueHt: '300.00' },
  ],
  totalHt: '2300.00',
  uninvoicedDeliveryNotes: { count: 1, totalHt: '4000.00' },
}

const BALANCES = {
  date: '2026-10-20',
  customerType: null,
  bucketDays: [15, 30, 60],
  rows: [
    {
      customerId: 5,
      name: 'Ferme Nord',
      phone: '0550 00 00 00',
      wilaya: 'Djelfa',
      notDue: '100.00',
      overdue: ['150.00', '200.00', '0.00', '80.00'],
      advance: '50.00',
      balance: '480.00',
      uninvoicedDeliveryNotes: '40.00',
    },
  ],
  totals: { notDue: '100.00', overdue: ['150.00', '200.00', '0.00', '80.00'], advance: '50.00', balance: '480.00', uninvoicedDeliveryNotes: '40.00' },
}

const TRACE = {
  lot: {
    id: 12,
    lotNumber: 'LOT-A',
    expiryDate: '2027-06-30',
    status: 'active',
    expired: false,
    quantityOnHand: '5.000',
    product: { id: 3, sku: 'IVM-1', name: 'Ivermectine', baseUnit: 'ml' },
  },
  movements: [
    { id: 1, at: '2026-10-01T08:00:00Z', type: 'purchase_receipt', quantity: '10.000', user: 'Amina Benali', document: { type: 'goods_receipt', id: 4, number: 'GR-2026-0004' }, party: { type: 'supplier', id: 2, name: 'Labo Sud' } },
    { id: 2, at: '2026-10-02T08:00:00Z', type: 'sale', quantity: '-2.000', user: 'Amina Benali', document: { type: 'receipt', id: 9, number: 'TC-2026-0009' }, party: null },
  ],
  suppliers: [{ id: 2, name: 'Labo Sud', received: '10.000', returned: '0.000' }],
  customers: [{ id: null, name: null, sold: '2.000', returned: '0.000', net: '2.000' }],
}

describe('Reports', () => {
  it('lists only the reports the user may open (BR-RPT-003)', async () => {
    serveMe(aUser({ permissions: ['report.sales'] }))
    renderApp('/reports')
    expect(await screen.findByRole('link', { name: /Chiffre d'affaires/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Statistiques des ventes/ })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Marge brute/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Créances clients/ })).not.toBeInTheDocument()
  })

  it('shows the revenue table with totals and exports the same filters as CSV and PDF (ADR-149)', async () => {
    serveMe(aUser({ permissions: ['report.sales'] }))
    const queries: string[] = []
    server.use(
      http.get('/api/reports/revenue', ({ request }) => {
        queries.push(new URL(request.url).search)
        return HttpResponse.json(REVENUE)
      }),
    )
    renderApp('/reports/revenue')

    const table = await screen.findByRole('table')
    expect(within(table).getByText('Antiparasitaires')).toBeInTheDocument()
    expect(within(table).getByText('Sans catégorie')).toBeInTheDocument()
    expect(within(table).getByText('Bons de livraison non facturés (1)')).toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText('Regroupement'), 'category')
    await screen.findByText('Antiparasitaires')
    expect(queries.at(-1)).toContain('groupBy=category')
    expect(screen.getByRole('link', { name: 'Exporter CSV' })).toHaveAttribute('href', '/api/reports/revenue?groupBy=category&format=csv')
    expect(screen.getByRole('link', { name: 'Imprimer / PDF' })).toHaveAttribute('href', '/api/reports/revenue?groupBy=category&format=pdf')
  })

  it('names the ageing columns from the setting and offers valuation at cost only with report.margin', async () => {
    serveMe(aUser({ permissions: ['report.finance', 'report.stock'] }))
    server.use(
      http.get('/api/reports/customer-balances', () => HttpResponse.json(BALANCES)),
      http.get('/api/reports/stock-valuation', () =>
        HttpResponse.json({ date: '2026-10-20', basis: 'sale_price', groupBy: 'product', rows: [], totalValue: '0.00', totalUnsellableValue: '0.00' }),
      ),
    )
    renderApp('/reports/customerBalances')
    expect(await screen.findByRole('columnheader', { name: '1–15 j' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '> 60 j' })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: /Ferme Nord/ })).toHaveTextContent('480,00')

    renderApp('/reports/stockValuation')
    const basis = await screen.findByLabelText('Valorisation')
    expect(within(basis).queryByRole('option', { name: 'Au coût' })).not.toBeInTheDocument()
  })

  it('traces a lot opened from its page, anonymous counter sales included (BR-RPT-005)', async () => {
    serveMe(aUser({ permissions: ['lot.recall'] }))
    let requested = false
    server.use(
      http.get('/api/reports/lot-traceability', ({ request }) => {
        requested = true
        expect(new URL(request.url).searchParams.get('lotId')).toBe('12')
        return HttpResponse.json(TRACE)
      }),
    )
    renderApp('/reports/lotTraceability')
    expect(await screen.findByText('Indiquez le lot à tracer.')).toBeInTheDocument()
    expect(requested).toBe(false)

    renderApp('/reports/lotTraceability?lotId=12')
    expect(await screen.findByRole('heading', { name: 'Amont : fournisseurs' })).toBeInTheDocument()
    expect(screen.getByText('Bon de réception GR-2026-0004')).toBeInTheDocument()
    expect(screen.getAllByText('Vente comptoir').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Labo Sud').length).toBe(2)
  })

  it('refuses a report the user may not open', async () => {
    serveMe(aUser({ permissions: ['report.sales'] }))
    renderApp('/reports/margin')
    expect(await screen.findByText("Vous n'avez pas l'autorisation pour cette action.")).toBeInTheDocument()
  })
})

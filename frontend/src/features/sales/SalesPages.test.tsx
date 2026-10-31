import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const SELLER = aUser({ permissions: ['sale.read', 'sale.create'] })
const PHARMACIST = aUser({ permissions: ['sale.read', 'sale.create', 'sale.cancel'] })
const READER = aUser({ permissions: ['sale.read'] })

function aSale(overrides: Record<string, unknown> = {}) {
  return {
    id: 300,
    docType: 'delivery_note',
    number: 'DN-2026-000001',
    status: 'confirmed',
    customer: { id: 12, name: 'Ferme Démo' },
    parentId: null,
    creditSale: true,
    dueDate: null,
    issuedAt: '2026-10-31T08:00:00Z',
    createdAt: '2026-10-31T08:00:00Z',
    cancelledAt: null,
    cancelReason: null,
    notes: null,
    totals: { ht: '20.00', discount: '0.00', vat: '1.80', ttc: '21.80' },
    lines: [
      { id: 90, productId: 5, productName: 'Vermifuge Démo', lotId: 21, lotNumber: 'L-21', expiryDate: '2027-12-31', unitId: null, unitName: 'tablet', targetSpeciesId: null, quantity: '2.000', quantityBase: '2.000', unitPrice: '10.0000', discountPct: '0.00', vatRate: '9.00', lineHt: '20.00', lineVat: '1.80', lineTtc: '21.80', warnings: [] },
    ],
    payments: [],
    cashSessionId: null,
    customerBalance: null,
    version: 2,
    ...overrides,
  }
}

const serveSale = (sale: Record<string, unknown>) => server.use(http.get('/api/sales/300', () => HttpResponse.json(sale)))

describe('sales documents (frontend.md §4.8)', () => {
  it('lists documents and filters by type', async () => {
    serveMe(SELLER)
    const urls: string[] = []
    server.use(
      http.get('/api/sales', ({ request }) => {
        urls.push(request.url)
        return HttpResponse.json({
          content: [
            { id: 300, docType: 'delivery_note', number: 'DN-2026-000001', status: 'confirmed', customer: { id: 12, name: 'Ferme Démo' }, creditSale: true, issuedAt: '2026-10-31T08:00:00Z', createdAt: '2026-10-31T08:00:00Z', totalTtc: '21.80', version: 2 },
            { id: 301, docType: 'receipt', number: null, status: 'draft', customer: null, creditSale: false, issuedAt: null, createdAt: '2026-10-31T09:00:00Z', totalTtc: '0.00', version: 0 },
          ],
          page: { number: 0, size: 20, totalElements: 2, totalPages: 1 },
        })
      }),
    )
    renderApp('/sales')

    expect(await screen.findByRole('link', { name: 'DN-2026-000001' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Brouillon 301' })).toBeInTheDocument()
    expect(screen.getByText('Anonyme')).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('Type'), 'quote')
    await waitFor(() => expect(urls.at(-1)).toContain('docType=quote'))
  })

  it('converts a delivery note and continues at the counter', async () => {
    serveMe(SELLER)
    serveSale(aSale())
    let body: unknown
    server.use(
      http.post('/api/sales/300/convert', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(aSale({ id: 301, docType: 'invoice', number: null, status: 'draft', parentId: 300 }), { status: 201 })
      }),
      http.get('/api/sales/301', () => HttpResponse.json(aSale({ id: 301, docType: 'invoice', number: null, status: 'draft', parentId: 300 }))),
      http.get('/api/cash-sessions/current', () => new HttpResponse(null, { status: 204 })),
      http.get('/api/animal-species', () => HttpResponse.json([])),
      http.get('/api/customers/12/balance', () => HttpResponse.json({ balance: '0.00', uninvoicedDeliveryNotes: '21.80', creditLimit: null, availableCredit: null })),
      http.get('/api/products', () => HttpResponse.json({ content: [], page: { number: 0, size: 10, totalElements: 0, totalPages: 0 } })),
    )
    renderApp('/sales/300')

    await userEvent.click(await screen.findByRole('button', { name: 'Convertir en : Facture' }))

    expect(await screen.findByTestId('pos-customer')).toHaveTextContent('Ferme Démo')
    expect(await screen.findByTestId('pos-total')).toHaveTextContent('21,80')
    expect(body).toEqual({ targetType: 'invoice' })
  })

  it('offers no conversion of an invoice and a quote can go to two types', async () => {
    serveMe(SELLER)
    serveSale(aSale({ docType: 'quote', number: 'QUO-2026-000001' }))
    renderApp('/sales/300')

    expect(await screen.findByRole('button', { name: 'Convertir en : Bon de livraison' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Convertir en : Facture' })).toBeInTheDocument()
  })

  it('shows why a conversion was refused', async () => {
    serveMe(SELLER)
    serveSale(aSale())
    server.use(http.post('/api/sales/300/convert', () => problem(409, 'INVALID_STATE_TRANSITION', { detail: 'This delivery note already has an invoice.' })))
    renderApp('/sales/300')

    await userEvent.click(await screen.findByRole('button', { name: 'Convertir en : Facture' }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('cancels with a reason, and a refusal keeps the dialog with its message', async () => {
    serveMe(PHARMACIST)
    let sale = aSale()
    let attempts = 0
    let reason: unknown
    server.use(
      http.get('/api/sales/300', () => HttpResponse.json(sale)),
      http.post('/api/sales/300/cancel', async ({ request }) => {
        attempts += 1
        if (attempts === 1) {
          return problem(409, 'SALE_NOT_CANCELLABLE', { context: { blockedBy: 'derived_document' } })
        }
        reason = await request.json()
        sale = aSale({ status: 'cancelled', cancelReason: 'Erreur' })
        return HttpResponse.json(sale)
      }),
    )
    renderApp('/sales/300')

    await userEvent.click(await screen.findByRole('button', { name: 'Annuler le document' }))
    await userEvent.type(screen.getByLabelText('Motif'), 'Erreur')
    await userEvent.click(screen.getByRole('button', { name: 'Confirmer' }))
    expect(await screen.findByText('Ce document ne peut pas être annulé : utilisez un avoir.')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Confirmer' }))
    await waitFor(() => expect(screen.getByTestId('sale-status')).toHaveTextContent('Annulé'))
    expect(reason).toEqual({ reason: 'Erreur' })
    expect(screen.queryByRole('button', { name: 'Annuler le document' })).not.toBeInTheDocument()
  })

  it('hides the actions without their permissions', async () => {
    serveMe(READER)
    serveSale(aSale())
    renderApp('/sales/300')

    await screen.findByTestId('sale-status')
    expect(screen.queryByRole('button', { name: /Convertir/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Annuler le document' })).not.toBeInTheDocument()
  })

  it('sends a draft to the counter', async () => {
    serveMe(SELLER)
    serveSale(aSale({ number: null, status: 'draft' }))
    renderApp('/sales/300')

    expect(await screen.findByRole('link', { name: 'Terminer en caisse' })).toHaveAttribute('href', '/pos?draft=300')
  })
})

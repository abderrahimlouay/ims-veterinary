import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const PHARMACIST = aUser({ permissions: ['sale.read', 'return.read', 'return.customer.create', 'cash.open'] })
const SELLER = aUser({ permissions: ['sale.read', 'sale.create', 'return.read'] })

const DOCUMENT = { id: 300, number: 'RCT-2026-000001', docType: 'receipt', creditSale: false, customer: null, issuedAt: '2026-10-31T08:00:00Z' }
const PRODUCT_A = { id: 5, sku: 'AMX', commercialName: 'Amoxival', baseUnit: 'tablet' }
const PRODUCT_B = { id: 6, sku: 'IVM', commercialName: 'Ivermec', baseUnit: 'ml' }

function preview(creditTtc = '0.00') {
  return {
    document: DOCUMENT,
    lines: [
      { saleLineId: 90, product: PRODUCT_A, lot: { id: 21, lotNumber: 'L-21', expiryDate: '2027-12-31', status: 'active' }, soldBase: '2.000', returnedBase: '0.000', returnableBase: '2.000', restockAllowed: true, restockRefusal: null },
      { saleLineId: 91, product: PRODUCT_B, lot: { id: 22, lotNumber: 'L-22', expiryDate: '2026-01-31', status: 'active' }, soldBase: '2.000', returnedBase: '0.000', returnableBase: '2.000', restockAllowed: false, restockRefusal: 'lot_expired' },
    ],
    creditNote: { totalHt: creditTtc === '0.00' ? '0.00' : '20.00', totalVat: creditTtc === '0.00' ? '0.00' : '1.80', totalTtc: creditTtc, lines: [] },
    refundRequired: true,
  }
}

const DETAIL = {
  id: 77,
  number: 'CR-2026-000001',
  document: { id: 300, number: 'RCT-2026-000001', docType: 'receipt' },
  customer: null,
  reason: 'Erreur de produit',
  createdAt: '2026-11-02T09:00:00Z',
  createdBy: 4,
  creditNote: { id: 310, number: 'CN-2026-000001', totalHt: '20.00', totalVat: '1.80', totalTtc: '21.80' },
  lines: [
    { id: 1, saleLineId: 90, product: PRODUCT_A, lot: { id: 21, lotNumber: 'L-21', expiryDate: '2027-12-31', status: 'active' }, quantityBase: '1.000', sealed: true, disposition: 'restock', discardReason: null, discardNote: null, movementIds: [501] },
    { id: 2, saleLineId: 91, product: PRODUCT_B, lot: { id: 22, lotNumber: 'L-22', expiryDate: '2026-01-31', status: 'active' }, quantityBase: '1.000', sealed: false, disposition: 'discard', discardReason: 'expired', discardNote: null, movementIds: [502, 503] },
  ],
  refunds: [{ id: 9, method: 'cash', amount: '21.80', reference: null, cashSessionId: 3 }],
}

const SESSION = { id: 3, status: 'open', openedAt: '2026-11-02T08:00:00Z', openingAmount: '100.00', expectedAmount: '150.00', countedAmount: null, varianceAmount: null, version: 0 }

describe('customer returns (frontend.md §4.8, W3)', () => {
  it('returns one line to stock, discards the other, reviews the credit note and refunds it in cash', async () => {
    serveMe(PHARMACIST)
    const previews: unknown[] = []
    let created: { body: unknown; key: string | null } | undefined
    server.use(
      http.post('/api/returns/customer/preview', async ({ request }) => {
        const body = (await request.json()) as { lines: unknown[] }
        previews.push(body)
        return HttpResponse.json(preview(body.lines.length === 0 ? '0.00' : '21.80'))
      }),
      http.get('/api/cash-sessions/current', () => HttpResponse.json(SESSION)),
      http.post('/api/returns/customer', async ({ request }) => {
        created = { body: await request.json(), key: request.headers.get('Idempotency-Key') }
        return HttpResponse.json(DETAIL, { status: 201 })
      }),
      http.get('/api/returns/customer/77', () => HttpResponse.json(DETAIL)),
    )
    renderApp('/returns/customer/new?documentId=300')

    const restockable = await screen.findByLabelText('Devenir de Amoxival')
    expect(within(restockable).getAllByRole('option').map((o) => o.textContent)).toEqual(['Remise en stock', 'Destruction'])
    const expired = screen.getByLabelText('Devenir de Ivermec')
    expect(within(expired).getAllByRole('option').map((o) => o.textContent)).toEqual(['Destruction'])
    expect(screen.getByText('Lot périmé : destruction seulement.')).toBeInTheDocument()
    expect(screen.getByLabelText('Motif de destruction de Ivermec')).toHaveValue('expired')

    await userEvent.type(screen.getByLabelText('Quantité retournée de Amoxival (unité de base)'), '1')
    await userEvent.type(screen.getByLabelText('Quantité retournée de Ivermec (unité de base)'), '1')
    await userEvent.type(screen.getByLabelText('Motif du retour'), 'Erreur de produit')
    await userEvent.click(screen.getByRole('button', { name: 'Vérifier' }))

    expect(await screen.findByTestId('credit-total')).toHaveTextContent('21,80')
    expect(screen.getByLabelText('Montant')).toHaveValue('21.80')
    await userEvent.click(screen.getByRole('button', { name: 'Confirmer le retour' }))

    expect(await screen.findByRole('heading', { name: 'CR-2026-000001' })).toBeInTheDocument()
    expect(previews[1]).toEqual({
      documentId: 300,
      lines: [
        { saleLineId: 90, quantity: '1', unit: 'base', sealed: true, disposition: 'restock' },
        { saleLineId: 91, quantity: '1', unit: 'base', sealed: true, disposition: 'discard', discardReason: 'expired' },
      ],
    })
    expect(created?.key).toMatch(/^[0-9a-f-]{36}$/)
    expect(created?.body).toEqual({
      documentId: 300,
      reason: 'Erreur de produit',
      lines: [
        { saleLineId: 90, quantity: '1', unit: 'base', sealed: true, disposition: 'restock' },
        { saleLineId: 91, quantity: '1', unit: 'base', sealed: true, disposition: 'discard', discardReason: 'expired' },
      ],
      refund: [{ method: 'cash', amount: '21.80', reference: null }],
    })
    expect(screen.getByTestId('credit-note')).toHaveTextContent('CN-2026-000001')
    expect(screen.getByRole('link', { name: 'Avoir (PDF)' })).toHaveAttribute('href', expect.stringMatching(/^\/api\/returns\/customer\/\d+\/pdf$/))
    expect(screen.getByText(/Destruction · Périmé/)).toBeInTheDocument()
  })

  it('an unsealed line can only be discarded and the server field error lands on its row', async () => {
    serveMe(PHARMACIST)
    server.use(
      http.post('/api/returns/customer/preview', async ({ request }) => {
        const body = (await request.json()) as { lines: unknown[] }
        return body.lines.length === 0
          ? HttpResponse.json(preview())
          : problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'lines[0].discardReason', code: 'REQUIRED', message: 'required' }] })
      }),
    )
    renderApp('/returns/customer/new?documentId=300')

    await userEvent.click(await screen.findByLabelText('Amoxival est scellé'))
    expect(within(screen.getByLabelText('Devenir de Amoxival')).getAllByRole('option').map((o) => o.textContent)).toEqual(['Destruction'])
    await userEvent.type(screen.getByLabelText('Quantité retournée de Amoxival (unité de base)'), '1')
    await userEvent.click(screen.getByRole('button', { name: 'Vérifier' }))

    await waitFor(() => expect(screen.getByLabelText('Motif de destruction de Amoxival')).toHaveAttribute('aria-invalid', 'true'))
    expect(screen.queryByRole('button', { name: 'Confirmer le retour' })).not.toBeInTheDocument()
  })

  it('shows why the server refused the return', async () => {
    serveMe(PHARMACIST)
    server.use(
      http.post('/api/returns/customer/preview', () => HttpResponse.json(preview('10.90'))),
      http.get('/api/cash-sessions/current', () => HttpResponse.json(SESSION)),
      http.post('/api/returns/customer', () => problem(422, 'CASH_INSUFFICIENT', { context: { expected: '5.00' } })),
    )
    renderApp('/returns/customer/new?documentId=300')

    await userEvent.type(await screen.findByLabelText('Quantité retournée de Amoxival (unité de base)'), '1')
    await userEvent.type(screen.getByLabelText('Motif du retour'), 'Erreur')
    await userEvent.click(screen.getByRole('button', { name: 'Vérifier' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Confirmer le retour' }))

    expect(await screen.findByText(/Pas assez d’espèces dans votre caisse/)).toBeInTheDocument()
  })

  it('lists the returns, and a seller sees no way to create one', async () => {
    serveMe(SELLER)
    server.use(
      http.get('/api/returns/customer', () =>
        HttpResponse.json({
          content: [{ id: 77, number: 'CR-2026-000001', document: DETAIL.document, customer: null, creditNote: DETAIL.creditNote, refunded: true, reason: 'Erreur', createdAt: '2026-11-02T09:00:00Z', createdBy: 4 }],
          page: { number: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      ),
    )
    renderApp('/returns/customer')

    expect(await screen.findByRole('link', { name: 'CR-2026-000001' })).toHaveAttribute('href', '/returns/customer/77')
    expect(screen.getByRole('link', { name: 'CN-2026-000001' })).toBeInTheDocument()
    expect(screen.getByText('Remboursé')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Nouveau retour client' })).not.toBeInTheDocument()
  })
})

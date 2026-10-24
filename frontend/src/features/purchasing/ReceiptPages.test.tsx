import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const RECEIVER = aUser({
  permissions: ['purchase.read', 'receipt.read', 'receipt.create', 'receipt.cancel', 'supplier.read', 'return.read',
    'return.supplier.create', 'lot.read', 'reorder.read', 'reorder.manage', 'purchase.create', 'product.read'],
})
const PRODUCT = { id: 5, sku: 'AMX-10', commercialName: 'Amoxival 500', baseUnit: 'tablet' }
const SUPPLIER = { id: 3, name: 'Biopharm', active: true }

function page(content: unknown[]) {
  return { content, page: { number: 0, size: 20, totalElements: content.length, totalPages: 1 } }
}

const SENT_ORDER = {
  id: 41,
  number: 'PO-2026-000041',
  supplier: SUPPLIER,
  status: 'sent',
  orderDate: '2026-10-20',
  expectedDate: null,
  lines: [{ id: 120, product: PRODUCT, unit: null, quantity: '60.000', quantityBase: '60.000', receivedBase: '20.000', outstandingBase: '40.000', unitPrice: '0.3500', discountPct: '0.00', vatRate: '9.00', lineHt: '21.00', lineVat: '1.89' }],
  totalHt: '21.00',
  totalVat: '1.89',
  totalTtc: '22.89',
  createdBy: { id: 7, fullName: 'Amina Benali' },
  createdAt: '2026-10-20T08:00:00Z',
  validatedAt: null,
  sentAt: null,
  cancelReason: null,
  cancelledBy: null,
  cancelledAt: null,
  closedReason: null,
  closedBy: null,
  closedAt: null,
  receipts: [],
  version: 2,
}

function aReceipt(overrides: Record<string, unknown> = {}) {
  return {
    id: 9,
    number: 'GR-2026-000009',
    status: 'confirmed',
    supplier: SUPPLIER,
    purchaseOrder: { id: 41, number: 'PO-2026-000041', status: 'partially_received' },
    supplierDocRef: null,
    notes: null,
    receivedAt: '2026-10-20T08:00:00Z',
    receivedBy: { id: 7, fullName: 'Amina Benali' },
    lines: [{ id: 1, poLineId: 120, product: PRODUCT, lotId: 21, lotNumber: 'L-NC', expiryDate: '2027-06-30', quantityBase: '40.000', unitCost: '0.3500', conforming: false, lotBlocked: true, movementId: 90 }],
    cancelReason: null,
    cancelledBy: null,
    cancelledAt: null,
    ...overrides,
  }
}

describe('goods receipts (frontend.md §4.5, W1b)', () => {
  it('receives the outstanding quantity, confirms a lot blocking explicitly and keeps one idempotency key', async () => {
    serveMe(RECEIVER)
    const calls: { key: string | null; body: Record<string, unknown> }[] = []
    server.use(
      http.get('/api/purchase-orders/41', () => HttpResponse.json(SENT_ORDER)),
      http.get('/api/products/5/units', () => HttpResponse.json([])),
      http.post('/api/goods-receipts', async ({ request }) => {
        const body = (await request.json()) as Record<string, unknown>
        calls.push({ key: request.headers.get('Idempotency-Key'), body })
        return body.confirmLotBlocking
          ? HttpResponse.json(aReceipt(), { status: 201 })
          : problem(409, 'LOT_BLOCK_CONFIRMATION_REQUIRED', { context: { lots: [{ lotId: 21, lotNumber: 'L-NC', productId: 5, quantityInStock: '12.000' }] } })
      }),
      http.get('/api/goods-receipts/9', () => HttpResponse.json(aReceipt())),
    )
    const router = renderApp('/goods-receipts/new?orderId=41')

    expect(await screen.findByLabelText('Quantité')).toHaveValue('40.000')
    await userEvent.type(screen.getByLabelText('N° de lot'), 'L-NC')
    await userEvent.type(screen.getByLabelText('Date de péremption'), '06/2027')
    await userEvent.type(screen.getByLabelText('Coût par unité de base'), '0,35')
    await userEvent.click(screen.getByLabelText('Conforme'))
    await userEvent.click(screen.getByRole('button', { name: 'Confirmer la réception' }))

    expect(await screen.findByText('L-NC : 12,000 déjà en stock')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Bloquer ces lots et confirmer' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/goods-receipts/9'))
    expect(calls).toHaveLength(2)
    expect(calls[0].key).toMatch(/^[0-9a-f-]{36}$/)
    expect(calls[1].key).toBe(calls[0].key)
    expect(calls[0].body).toEqual({
      supplierId: 3,
      purchaseOrderId: 41,
      supplierDocRef: null,
      notes: null,
      confirmLotBlocking: false,
      lines: [{ poLineId: 120, productId: 5, unitId: null, quantity: '40.000', lotNumber: 'L-NC', expiryDate: '2027-06-30', unitCost: '0.35', conforming: false }],
    })
    expect(await screen.findByText('Non conforme — lot bloqué')).toBeInTheDocument()
  })

  it('flags an expired conforming line before sending', async () => {
    serveMe(RECEIVER)
    server.use(
      http.get('/api/purchase-orders/41', () => HttpResponse.json(SENT_ORDER)),
      http.get('/api/products/5/units', () => HttpResponse.json([])),
    )
    renderApp('/goods-receipts/new?orderId=41')

    await userEvent.type(await screen.findByLabelText('Date de péremption'), '01/2020')

    expect(screen.getByText('Ce lot est déjà périmé.')).toBeInTheDocument()
  })

  it('cancels a receipt with a reason and names the lot that moved when refused', async () => {
    serveMe(RECEIVER)
    let reason: unknown
    server.use(
      http.get('/api/goods-receipts/9', () => HttpResponse.json(aReceipt())),
      http.post('/api/goods-receipts/9/cancel', async ({ request }) => {
        reason = await request.json()
        return problem(409, 'RECEIPT_NOT_CANCELLABLE', { context: { lotId: 21, lotNumber: 'L-NC', movementId: 95, movementType: 'adjustment_breakage', movementAt: '2026-10-20T09:00:00Z' } })
      }),
    )
    renderApp('/goods-receipts/9')

    await userEvent.click(await screen.findByRole('button', { name: 'Annuler la réception' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Motif'), 'Wrong delivery')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmer' }))

    expect(await screen.findByText(/Le lot L-NC a bougé depuis \(Casse du .*\) : la réception ne peut plus être annulée\./)).toBeInTheDocument()
    expect(reason).toEqual({ reason: 'Wrong delivery' })
  })
})

describe('supplier returns and reorder conversion (frontend.md §4.5)', () => {
  it('returns a lot and places a lot of another supplier on its line', async () => {
    serveMe(RECEIVER)
    let sent: unknown
    server.use(
      http.get('/api/suppliers', () => HttpResponse.json(page([{ ...SUPPLIER, kind: 'wholesaler', taxId: null, phone: null, email: null, address: null, paymentTermsDays: 30, createdAt: '', updatedAt: '', version: 0 }]))),
      http.get('/api/lots', () => HttpResponse.json(page([{ id: 21, product: PRODUCT, lotNumber: 'L-RET', expiryDate: '2027-05-31', quantityOnHand: '12.000', status: 'blocked', alertLevel: null, daysLeft: 200 }]))),
      http.post('/api/returns/supplier', async ({ request }) => {
        sent = await request.json()
        return problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'lines[0].lotId', code: 'OTHER_SUPPLIER', message: 'x' }] })
      }),
    )
    renderApp('/supplier-returns/new')

    await screen.findByRole('option', { name: 'Biopharm' })
    await userEvent.selectOptions(screen.getByLabelText('Fournisseur'), '3')
    await userEvent.type(screen.getByLabelText('Motif'), 'Damaged boxes')
    await userEvent.type(screen.getByLabelText('Rechercher un lot par son numéro'), 'L-RET')
    await userEvent.click(await screen.findByRole('button', { name: 'Choisir le lot L-RET' }))
    await userEvent.type(screen.getByLabelText('Quantité (tablet)'), '4')
    await userEvent.click(screen.getByRole('button', { name: 'Confirmer le retour' }))

    expect(await screen.findByText("Ce lot a été reçu d'un autre fournisseur.")).toBeInTheDocument()
    expect(sent).toEqual({ supplierId: 3, reason: 'Damaged boxes', lines: [{ lotId: 21, quantity: '4', unitId: null }] })
  })

  it('converts selected pending proposals into a draft for the chosen supplier', async () => {
    serveMe(RECEIVER)
    let sent: unknown
    server.use(
      http.get('/api/suppliers', () => HttpResponse.json(page([{ ...SUPPLIER, kind: 'wholesaler', taxId: null, phone: null, email: null, address: null, paymentTermsDays: 30, createdAt: '', updatedAt: '', version: 0 }]))),
      http.get('/api/inventory/reorder-proposals', () =>
        HttpResponse.json(page([{ id: 11, product: { ...PRODUCT, divisible: false }, currentQty: '2.000', suggestedQty: '48.000', reorderPoint: '10.000', maxStock: '50.000', status: 'pending', createdAt: '2026-10-20T08:00:00Z', updatedAt: '2026-10-20T08:00:00Z', dismissedAt: null, dismissedBy: null, supplierId: null }])),
      ),
      http.post('/api/inventory/reorder-proposals/convert', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json([{ ...SENT_ORDER, id: 42, number: 'PO-2026-000042', status: 'draft' }], { status: 201 })
      }),
    )
    renderApp('/reorder')

    await userEvent.click(await screen.findByLabelText('Sélectionner Amoxival 500'))
    await screen.findByRole('option', { name: 'Biopharm' })
    await userEvent.selectOptions(screen.getByLabelText('Fournisseur de la commande'), '3')
    await userEvent.click(screen.getByRole('button', { name: 'Créer le brouillon (1 sélectionnée(s))' }))

    expect(await screen.findByRole('link', { name: 'PO-2026-000042' })).toHaveAttribute('href', '/purchase-orders/42')
    expect(sent).toEqual({ groups: [{ supplierId: 3, proposalIds: [11] }] })
  })
})

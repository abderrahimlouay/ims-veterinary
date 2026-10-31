import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const SELLER = aUser({ permissions: ['sale.read', 'sale.create', 'product.read', 'customer.read', 'cash.open'] })
const EXPERT = aUser({ permissions: ['sale.read', 'sale.create', 'sale.discount', 'sale.override_fefo', 'product.read', 'customer.read', 'lot.read', 'cash.open'] })

const PRODUCT = { id: 5, sku: 'P-5', commercialName: 'Vermifuge Démo', baseUnit: 'tablet', sellableQtyBase: '10.000' }

function aLine(overrides: Record<string, unknown> = {}) {
  return {
    id: 90,
    productId: 5,
    productName: 'Vermifuge Démo',
    lotId: 21,
    lotNumber: 'L-21',
    expiryDate: '2027-12-31',
    unitId: null,
    unitName: 'tablet',
    targetSpeciesId: null,
    quantity: '2.000',
    quantityBase: '2.000',
    unitPrice: '10.0000',
    discountPct: '0.00',
    vatRate: '9.00',
    lineHt: '20.00',
    lineVat: '1.80',
    lineTtc: '21.80',
    warnings: [],
    ...overrides,
  }
}

function aSale(overrides: Record<string, unknown> = {}) {
  return {
    id: 300,
    docType: 'receipt',
    number: null,
    status: 'draft',
    customer: null,
    parentId: null,
    creditSale: false,
    dueDate: null,
    issuedAt: null,
    createdAt: '2026-10-31T08:00:00Z',
    cancelledAt: null,
    cancelReason: null,
    notes: null,
    totals: { ht: '20.00', discount: '0.00', vat: '1.80', ttc: '21.80' },
    lines: [aLine()],
    payments: [],
    cashSessionId: null,
    customerBalance: null,
    version: 1,
    ...overrides,
  }
}

const SESSION = { id: 8, status: 'open', openedAt: '2026-10-31T08:00:00Z', openingAmount: '50.00', expectedAmount: '50.00', countedAmount: null, varianceAmount: null, version: 0 }

/** The endpoints of a counter sale up to a line in the cart; a test adds what it checks. */
function serveCounter(sale = aSale()) {
  server.use(
    http.get('/api/cash-sessions/current', () => HttpResponse.json(SESSION)),
    http.get('/api/animal-species', () => HttpResponse.json([])),
    http.get('/api/products', () => HttpResponse.json({ content: [PRODUCT], page: { number: 0, size: 10, totalElements: 1, totalPages: 1 } })),
    http.post('/api/sales', () => HttpResponse.json(aSale({ lines: [], totals: { ht: '0.00', discount: '0.00', vat: '0.00', ttc: '0.00' } }), { status: 201 })),
    http.post('/api/sales/300/lines', () => HttpResponse.json(sale)),
  )
}

async function addProduct() {
  await userEvent.type(await screen.findByLabelText('Scanner ou chercher un produit'), 'Vermifuge')
  await userEvent.click(await screen.findByRole('button', { name: 'Ajouter Vermifuge Démo' }))
  await screen.findByTestId('pos-line')
}

describe('counter sale (frontend.md §4.7, W2)', () => {
  beforeEach(() => serveMe(SELLER))

  it('sends the total as the payment, never what was tendered, with an idempotency key (ADR-118)', async () => {
    serveCounter()
    let body: unknown
    let key: string | null = null
    server.use(
      http.post('/api/sales/300/confirm', async ({ request }) => {
        body = await request.json()
        key = request.headers.get('Idempotency-Key')
        return HttpResponse.json(aSale({ status: 'confirmed', number: 'RCT-2026-000001' }))
      }),
    )
    renderApp('/pos')
    await addProduct()

    await userEvent.type(screen.getByLabelText('Espèces reçues'), '50')
    expect(screen.getByTestId('pos-change')).toHaveTextContent('28,20')
    await userEvent.click(screen.getByRole('button', { name: 'Confirmer la vente' }))

    expect(await screen.findByText('Document RCT-2026-000001')).toBeInTheDocument()
    expect(screen.getByText(/Monnaie à rendre/)).toBeInTheDocument()
    expect(body).toEqual({ payments: [{ method: 'cash', amount: '21.80', reference: null }] })
    expect(key).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('keeps one key for a retry of the same payment', async () => {
    serveCounter()
    const keys: (string | null)[] = []
    server.use(
      http.post('/api/sales/300/confirm', ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        return keys.length === 1
          ? problem(409, 'CASH_SESSION_REQUIRED')
          : HttpResponse.json(aSale({ status: 'confirmed', number: 'RCT-2026-000002' }))
      }),
    )
    renderApp('/pos')
    await addProduct()

    await userEvent.click(screen.getByRole('button', { name: 'Confirmer la vente' }))
    expect(await screen.findByRole('dialog', { name: 'Ouvrir une session de caisse' })).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    await userEvent.click(screen.getByRole('button', { name: 'Confirmer la vente' }))

    await screen.findByText('Document RCT-2026-000002')
    expect(keys[1]).toBe(keys[0])
  })

  it('highlights the line of a lot refused at confirmation and sells nothing', async () => {
    serveCounter()
    server.use(
      http.post('/api/sales/300/confirm', () => problem(422, 'LOT_BLOCKED', { context: { lotId: 21 } })),
      http.get('/api/sales/300', () => HttpResponse.json(aSale())),
    )
    renderApp('/pos')
    await addProduct()

    await userEvent.click(screen.getByRole('button', { name: 'Confirmer la vente' }))

    expect(await screen.findByText('Ce lot est bloqué et ne peut pas être vendu.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByTestId('pos-line')).toHaveAttribute('data-invalid', 'true'))
    expect(screen.getByRole('button', { name: 'Confirmer la vente' })).toBeEnabled()
  })

  it('shows the warnings of a line without blocking the sale (ADR-121)', async () => {
    serveCounter(
      aSale({
        lines: [
          aLine({
            warnings: [
              { code: 'LOT_NEAR_EXPIRY', productId: 5, expiryDate: '2026-11-15', level: 'critical', speciesName: null, meatDays: null, milkDays: null, eggsDays: null },
              { code: 'WITHDRAWAL_PERIOD', productId: 5, expiryDate: null, level: null, speciesName: 'Ovin', meatDays: 28, milkDays: 7, eggsDays: null },
              { code: 'LOW_STOCK_AFTER_SALE', productId: 5, expiryDate: null, level: null, speciesName: null, meatDays: null, milkDays: null, eggsDays: null },
            ],
          }),
        ],
      }),
    )
    renderApp('/pos')
    await addProduct()

    expect(screen.getByText(/Péremption proche/)).toBeInTheDocument()
    expect(screen.getByText(/Délai d'attente, Ovin : viande 28 j, lait 7 j/)).toBeInTheDocument()
    expect(screen.getByText('Stock bas après cette vente')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Confirmer la vente' })).toBeEnabled()
  })

  it('needs a customer before the first line of an invoice, and a receipt is never a credit sale', async () => {
    serveCounter()
    renderApp('/pos')

    await screen.findByLabelText('Document')
    expect(screen.queryByLabelText('Vente à crédit')).not.toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('Document'), 'invoice')
    expect(screen.getByText("Choisissez d'abord un client.")).toBeInTheDocument()
    expect(screen.getByLabelText('Vente à crédit')).toBeDisabled()
  })

  it('takes no payment on a credit sale and shows the credit state', async () => {
    serveCounter(aSale({ docType: 'invoice', creditSale: true, customer: { id: 12, name: 'Ferme Démo' } }))
    let body: unknown
    server.use(
      http.get('/api/customers', () =>
        HttpResponse.json({ content: [{ id: 12, name: 'Ferme Démo' }], page: { number: 0, size: 5, totalElements: 1, totalPages: 1 } }),
      ),
      http.get('/api/customers/12/balance', () =>
        HttpResponse.json({ balance: '10.00', uninvoicedDeliveryNotes: '0.00', creditLimit: '100.00', availableCredit: '90.00' }),
      ),
      http.post('/api/sales/300/confirm', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(aSale({ status: 'confirmed', number: 'INV-2026-000001', customerBalance: '31.80' }))
      }),
    )
    renderApp('/pos')

    await userEvent.selectOptions(await screen.findByLabelText('Document'), 'invoice')
    await userEvent.type(screen.getByLabelText('Client'), 'Ferme')
    await userEvent.click(await screen.findByRole('button', { name: 'Choisir Ferme Démo' }))
    await userEvent.click(screen.getByLabelText('Vente à crédit'))
    expect(await screen.findByTestId('pos-credit-state')).toHaveTextContent('90,00')
    await addProduct()

    expect(screen.queryByLabelText('Mode')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Confirmer la vente' }))
    await screen.findByText('Document INV-2026-000001')
    expect(body).toEqual({ payments: [] })
  })

  it('says so when the server cannot be reached', async () => {
    serveCounter()
    server.use(http.post('/api/sales/300/confirm', () => HttpResponse.error()))
    renderApp('/pos')
    await addProduct()

    await userEvent.click(screen.getByRole('button', { name: 'Confirmer la vente' }))

    expect(await screen.findByText(/Le serveur est injoignable/)).toBeInTheDocument()
  })

  it('offers another lot only with sale.override_fefo, and only sellable lots', async () => {
    serveMe(EXPERT)
    serveCounter()
    let write: unknown
    server.use(
      http.get('/api/products/5/units', () => HttpResponse.json([])),
      http.get('/api/products/5/lots', () =>
        HttpResponse.json([
          { id: 21, lotNumber: 'L-21', expiryDate: '2027-12-31', quantityOnHand: '5.000', status: 'active' },
          { id: 22, lotNumber: 'L-22', expiryDate: '2028-12-31', quantityOnHand: '5.000', status: 'active' },
          { id: 23, lotNumber: 'L-BLOCKED', expiryDate: '2028-12-31', quantityOnHand: '5.000', status: 'blocked' },
        ]),
      ),
      http.put('/api/sales/300/lines/90', async ({ request }) => {
        write = await request.json()
        return HttpResponse.json(aSale({ lines: [aLine({ lotId: 22, lotNumber: 'L-22' })] }))
      }),
    )
    renderApp('/pos')
    await addProduct()

    await userEvent.click(screen.getByRole('button', { name: 'Modifier Vermifuge Démo' }))
    const lot = await screen.findByLabelText('Lot')
    await waitFor(() => expect(screen.getByRole('option', { name: /L-22/ })).toBeInTheDocument())
    expect(screen.queryByRole('option', { name: /L-BLOCKED/ })).not.toBeInTheDocument()
    await userEvent.selectOptions(lot, screen.getByRole('option', { name: /L-22/ }))
    await userEvent.type(screen.getByLabelText("Motif du choix d'un autre lot"), 'Demande du client')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await screen.findByText(/Lot L-22/)
    expect(write).toMatchObject({ lotId: 22, fefoOverrideReason: 'Demande du client' })
  })

  it('hides the discount and the lot choice without their permissions', async () => {
    serveCounter()
    server.use(http.get('/api/products/5/units', () => HttpResponse.json([])))
    renderApp('/pos')
    await addProduct()

    await userEvent.click(screen.getByRole('button', { name: 'Modifier Vermifuge Démo' }))
    await screen.findByLabelText('Unité')
    expect(screen.queryByLabelText('Remise manuelle (%)')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Lot')).not.toBeInTheDocument()
  })
})

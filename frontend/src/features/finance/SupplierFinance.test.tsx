import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const ACCOUNTANT = aUser({
  permissions: ['supplier.read', 'receipt.read', 'return.read', 'payment.read', 'payment.supplier.create', 'payment.reverse', 'supplierinvoice.manage'],
})
const READER = aUser({ permissions: ['supplier.read', 'payment.read'] })
const BUYER = aUser({ permissions: ['supplier.read', 'supplier.manage'] })

function page(content: unknown[]) {
  return { content, page: { number: 0, size: 20, totalElements: content.length, totalPages: 1 } }
}

const SUPPLIER = {
  id: 3,
  name: 'Biopharm',
  kind: 'wholesaler',
  taxId: null,
  phone: null,
  email: null,
  address: null,
  paymentTermsDays: 30,
  active: true,
  createdAt: '2026-10-20T08:00:00Z',
  updatedAt: '2026-10-20T08:00:00Z',
  version: 0,
}

const PAYABLES = {
  supplier: { id: 3, name: 'Biopharm' },
  balance: '1180.00',
  invoices: [
    { id: 50, invoiceNumber: 'BP-100', invoiceDate: '2026-10-01', dueDate: '2026-10-31', totalTtc: '1000.00', open: '800.00' },
    { id: 51, invoiceNumber: 'BP-101', invoiceDate: '2026-10-10', dueDate: '2026-11-09', totalTtc: '500.00', open: '500.00' },
  ],
  creditNotes: [{ id: 52, invoiceNumber: 'AV-7', totalTtc: '70.00', unapplied: '70.00' }],
  advances: [{ paymentId: 8, paidAt: '2026-10-20T09:00:00Z', unallocated: '50.00' }],
}

const PAYMENTS = page([
  { id: 8, supplierId: 3, method: 'bank_transfer', amount: '250.00', reference: 'VIR-8', paidAt: '2026-10-20T09:00:00Z', reverses: null, reversed: false },
  { id: 6, supplierId: 3, method: 'cheque', amount: '40.00', reference: 'CHQ-6', paidAt: '2026-10-12T09:00:00Z', reverses: null, reversed: true },
  { id: 7, supplierId: 3, method: 'cheque', amount: '40.00', reference: 'CHQ-6', paidAt: '2026-10-13T09:00:00Z', reverses: 6, reversed: false },
])

function anInvoice(overrides: Record<string, unknown> = {}) {
  return {
    id: 50,
    supplier: { id: 3, name: 'Biopharm' },
    invoiceNumber: 'BP-100',
    creditNote: false,
    receipt: { id: 4, number: 'GR-2026-000004' },
    supplierReturn: null,
    invoiceDate: '2026-10-01',
    dueDate: '2026-10-31',
    totalTtc: '1000.00',
    open: '1000.00',
    status: 'recorded',
    applications: [],
    payments: [],
    cancelReason: null,
    cancelledBy: null,
    cancelledAt: null,
    createdBy: 1,
    createdAt: '2026-10-01T09:00:00Z',
    warnings: [],
    ...overrides,
  }
}

function serveSupplier() {
  server.use(
    http.get('/api/suppliers/3', () => HttpResponse.json(SUPPLIER)),
    http.get('/api/suppliers/3/prices', () => HttpResponse.json(page([]))),
    http.get('/api/suppliers/3/payables', () => HttpResponse.json(PAYABLES)),
    http.get('/api/payments/suppliers', () => HttpResponse.json(PAYMENTS)),
  )
}

async function openPayables() {
  renderApp('/suppliers/3')
  await userEvent.click(await screen.findByRole('tab', { name: 'Dettes' }))
}

describe('supplier payables and payments (frontend.md §4.5, ADR-132, ADR-135, BR-PAY-014)', () => {
  it('shows the balance, the open invoices by due date, the credit notes, the advances and the payments', async () => {
    serveMe(READER)
    serveSupplier()
    await openPayables()

    expect(await screen.findByTestId('supplier-balance')).toHaveTextContent('1 180,00')
    const rows = screen.getAllByTestId('payable-invoice')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('BP-100')
    expect(rows[0]).toHaveTextContent('800,00')
    expect(screen.getByTestId('unapplied-credit-note')).toHaveTextContent('AV-7')
    expect(screen.getByTestId('supplier-advance')).toHaveTextContent('50,00')
    const payments = await screen.findAllByTestId('supplier-payment')
    expect(payments[1]).toHaveTextContent('annulé')
    expect(payments[2]).toHaveTextContent('annule le paiement n° 6')
    expect(screen.queryByRole('button', { name: 'Enregistrer un paiement' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Annuler le paiement' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Saisir une facture' })).toBeNull()
  })

  it('records a payment split oldest first by the server, with an idempotency key; cash needs no reference', async () => {
    serveMe(ACCOUNTANT)
    serveSupplier()
    let sent: { body: unknown; key: string | null } | undefined
    server.use(
      http.post('/api/payments/suppliers', async ({ request }) => {
        sent = { body: await request.json(), key: request.headers.get('Idempotency-Key') }
        return HttpResponse.json({ id: 9 }, { status: 201 })
      }),
    )
    await openPayables()
    await userEvent.click(await screen.findByRole('button', { name: 'Enregistrer un paiement' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.selectOptions(within(dialog).getByLabelText('Mode de paiement'), 'cash')
    expect(within(dialog).queryByLabelText('Référence (n° de chèque ou de virement)')).toBeNull()
    expect(within(dialog).getByText('Les espèces sortent de votre session de caisse ouverte.')).toBeInTheDocument()
    await userEvent.type(within(dialog).getByLabelText('Montant'), '300')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(sent).toBeDefined())
    expect(sent?.body).toEqual({ supplierId: 3, method: 'cash', amount: '300', reference: null })
    expect(sent?.key).toMatch(/^[0-9a-f-]{36}$/)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('places an allocation error on the invoice it names, counting only the amounts sent', async () => {
    serveMe(ACCOUNTANT)
    serveSupplier()
    let body: unknown
    server.use(
      http.post('/api/payments/suppliers', async ({ request }) => {
        body = await request.json()
        return problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'allocations[0].amount', code: 'SCALE', message: '' }] })
      }),
    )
    await openPayables()
    await userEvent.click(await screen.findByRole('button', { name: 'Enregistrer un paiement' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Montant'), '10')
    await userEvent.type(within(dialog).getByLabelText('Référence (n° de chèque ou de virement)'), 'VIR-9')
    await userEvent.click(within(dialog).getByLabelText('Choisir les factures'))
    const second = within(dialog).getByLabelText('BP-101 (reste dû 500,00 DA)')
    await userEvent.type(second, '1.234')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    expect(await within(dialog).findByText('Trop de décimales.')).toBeInTheDocument()
    expect(second).toHaveAttribute('aria-invalid', 'true')
    expect(within(dialog).getByLabelText('BP-100 (reste dû 800,00 DA)')).toHaveAttribute('aria-invalid', 'false')
    expect(body).toEqual({ supplierId: 3, method: 'bank_transfer', amount: '10', reference: 'VIR-9', allocations: [{ invoiceId: 51, amount: '1.234' }] })
  })

  it('applies an advance and reverses a payment in force with its reason', async () => {
    serveMe(ACCOUNTANT)
    serveSupplier()
    let applied: { body: unknown; key: string | null } | undefined
    let reversed: { url: string; body: unknown } | undefined
    server.use(
      http.post('/api/payments/suppliers/8/allocations', async ({ request }) => {
        applied = { body: await request.json(), key: request.headers.get('Idempotency-Key') }
        return HttpResponse.json({ id: 8 })
      }),
      http.post('/api/payments/suppliers/:id/reverse', async ({ request }) => {
        reversed = { url: request.url, body: await request.json() }
        return problem(409, 'CASH_SESSION_REQUIRED')
      }),
    )
    await openPayables()
    await userEvent.click(await screen.findByRole('button', { name: 'Imputer' }))
    let dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('BP-100 (reste dû 800,00 DA)'), '50')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Imputer' }))
    await waitFor(() => expect(applied?.body).toEqual({ allocations: [{ invoiceId: 50, amount: '50' }] }))
    expect(applied?.key).toMatch(/^[0-9a-f-]{36}$/)

    const reverseButtons = await screen.findAllByRole('button', { name: 'Annuler le paiement' })
    expect(reverseButtons).toHaveLength(1)
    await userEvent.click(reverseButtons[0])
    dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Motif'), 'Virement rejeté')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmer' }))
    expect(await within(dialog).findByText('Ouvrez votre session de caisse pour tout mouvement d’espèces.')).toBeInTheDocument()
    expect(reversed?.url).toContain('/api/payments/suppliers/8/reverse')
    expect(reversed?.body).toEqual({ reason: 'Virement rejeté' })
  })

  it('has no Payables tab without payment.read', async () => {
    serveMe(BUYER)
    serveSupplier()
    renderApp('/suppliers/3')
    expect(await screen.findByRole('heading', { name: 'Biopharm' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Dettes' })).toBeNull()
  })
})

describe('supplier invoices and credit notes (frontend.md §4.5, ADR-133, BR-PAY-015)', () => {
  it('lists invoices with the filters in the address', async () => {
    serveMe(ACCOUNTANT)
    const urls: string[] = []
    server.use(
      http.get('/api/suppliers', () => HttpResponse.json(page([SUPPLIER]))),
      http.get('/api/supplier-invoices', ({ request }) => {
        urls.push(request.url)
        return HttpResponse.json(
          page([
            { id: 50, supplierId: 3, supplierName: 'Biopharm', invoiceNumber: 'BP-100', creditNote: false, invoiceDate: '2026-10-01', dueDate: '2026-10-31', totalTtc: '1000.00', open: '800.00', status: 'recorded' },
          ]),
        )
      }),
    )
    renderApp('/supplier-invoices')
    expect(await screen.findByTestId('supplier-invoice')).toHaveTextContent('800,00')
    await userEvent.click(screen.getByLabelText('Restant à régler seulement'))
    await userEvent.selectOptions(screen.getByLabelText('Type'), 'true')
    await waitFor(() => expect(urls.at(-1)).toContain('creditNote=true'))
    expect(urls.at(-1)).toContain('open=true')
  })

  it('records an invoice, puts a duplicate number on its field and shows the receipt-total warning once recorded', async () => {
    serveMe(ACCOUNTANT)
    const bodies: unknown[] = []
    server.use(
      http.get('/api/suppliers', () => HttpResponse.json(page([SUPPLIER]))),
      http.get('/api/goods-receipts', () =>
        HttpResponse.json(
          page([{ id: 4, number: 'GR-2026-000004', supplier: { id: 3, name: 'Biopharm', active: true }, purchaseOrder: null, receivedAt: '2026-09-30T09:00:00Z', status: 'confirmed' }]),
        ),
      ),
      http.get('/api/returns/supplier', () => HttpResponse.json(page([]))),
      http.post('/api/supplier-invoices', async ({ request }) => {
        bodies.push(await request.json())
        return bodies.length === 1
          ? problem(409, 'DUPLICATE_RESOURCE', { context: { field: 'invoiceNumber' } })
          : HttpResponse.json(anInvoice({ warnings: [{ code: 'RECEIPT_TOTAL_DIFFERS', context: { receiptId: 4, receiptValue: '990.00' } }] }), { status: 201 })
      }),
      http.get('/api/supplier-invoices/50', () => HttpResponse.json(anInvoice())),
    )
    renderApp('/supplier-invoices/new?supplierId=3')
    await userEvent.click(await screen.findByRole('button', { name: 'Enregistrer' }))
    expect(screen.getAllByText('Ce champ est obligatoire.')).toHaveLength(3)
    expect(bodies).toHaveLength(0)

    await userEvent.type(screen.getByLabelText('N° fournisseur'), 'BP-100')
    await userEvent.type(screen.getByLabelText('Date de facture'), '2026-10-01')
    await userEvent.type(screen.getByLabelText('Total TTC'), '1000')
    await userEvent.selectOptions(await screen.findByLabelText('Réception'), '4')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(await screen.findByText('Cette valeur est déjà utilisée.')).toBeInTheDocument()
    expect(bodies[0]).toEqual({
      supplierId: 3,
      invoiceNumber: 'BP-100',
      receiptId: 4,
      supplierReturnId: null,
      invoiceDate: '2026-10-01',
      dueDate: null,
      totalTtc: '1000',
      creditNote: false,
    })

    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(await screen.findByText(/Le total diffère de la valeur de la réception \(990,00/)).toBeInTheDocument()
    expect(screen.getByTestId('invoice-open')).toHaveTextContent('1 000,00')
  })

  it('cancels an unsettled invoice with a reason', async () => {
    serveMe(ACCOUNTANT)
    let cancelled: unknown
    server.use(
      http.get('/api/supplier-invoices/50', () => HttpResponse.json(anInvoice())),
      http.post('/api/supplier-invoices/50/cancel', async ({ request }) => {
        cancelled = await request.json()
        return HttpResponse.json(anInvoice({ status: 'cancelled', open: '0.00', cancelReason: 'Doublon' }))
      }),
    )
    renderApp('/supplier-invoices/50')
    await userEvent.click(await screen.findByRole('button', { name: 'Annuler la saisie' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Motif'), 'Doublon')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmer' }))
    await waitFor(() => expect(cancelled).toEqual({ reason: 'Doublon' }))
  })

  it('offers no cancel once a payment settles the invoice', async () => {
    serveMe(ACCOUNTANT)
    server.use(
      http.get('/api/supplier-invoices/51', () =>
        HttpResponse.json(anInvoice({ id: 51, open: '900.00', payments: [{ id: 1, paymentId: 8, amount: '100.00', createdAt: '2026-10-20T09:00:00Z' }] })),
      ),
    )
    renderApp('/supplier-invoices/51')
    expect(await screen.findByTestId('invoice-payment')).toHaveTextContent('Paiement n° 8')
    expect(screen.queryByRole('button', { name: 'Annuler la saisie' })).toBeNull()
  })

  it('applies a credit note to open invoices of its supplier with an idempotency key', async () => {
    serveMe(ACCOUNTANT)
    let applied: { body: unknown; key: string | null } | undefined
    server.use(
      http.get('/api/supplier-invoices/52', () =>
        HttpResponse.json(anInvoice({ id: 52, invoiceNumber: 'AV-7', creditNote: true, receipt: null, totalTtc: '70.00', open: '70.00' })),
      ),
      http.get('/api/suppliers/3/payables', () => HttpResponse.json(PAYABLES)),
      http.post('/api/supplier-invoices/52/applications', async ({ request }) => {
        applied = { body: await request.json(), key: request.headers.get('Idempotency-Key') }
        return HttpResponse.json(anInvoice({ id: 52, creditNote: true, open: '0.00' }))
      }),
    )
    renderApp('/supplier-invoices/52')
    await userEvent.click(await screen.findByRole('button', { name: 'Imputer sur des factures' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.type(await within(dialog).findByLabelText('BP-101 (reste dû 500,00 DA)'), '70')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Imputer sur des factures' }))
    await waitFor(() => expect(applied?.body).toEqual({ allocations: [{ invoiceId: 51, amount: '70' }] }))
    expect(applied?.key).toMatch(/^[0-9a-f-]{36}$/)
  })
})

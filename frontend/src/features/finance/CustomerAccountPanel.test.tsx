import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const ACCOUNTANT = aUser({ permissions: ['customer.read', 'payment.read', 'payment.customer.create', 'payment.reverse', 'statement.read'] })
const READER = aUser({ permissions: ['customer.read', 'payment.read', 'statement.read'] })
const PHARMACIST = aUser({ permissions: ['customer.read', 'customer.manage', 'sale.read'] })

const CUSTOMER = {
  id: 12,
  name: 'Ferme Démo Compte',
  customerType: 'breeder',
  category: null,
  farmingType: null,
  wilaya: null,
  commune: null,
  address: null,
  phone: null,
  email: null,
  taxId: null,
  paymentTermsDays: 30,
  veterinarian: null,
  creditLimit: null,
  active: true,
  createdAt: '2026-10-25T08:00:00Z',
  updatedAt: '2026-10-25T08:00:00Z',
  version: 1,
}

const OPEN = {
  invoices: [
    { id: 40, number: 'INV-2026-000040', issuedAt: '2026-10-01T09:00:00Z', dueDate: '2026-10-31', totalTtc: '100.00', open: '80.00' },
    { id: 41, number: 'INV-2026-000041', issuedAt: '2026-10-10T09:00:00Z', dueDate: '2026-11-09', totalTtc: '50.00', open: '50.00' },
  ],
  advances: [{ paymentId: 7, paidAt: '2026-10-20T09:00:00Z', unallocated: '15.00' }],
}

const STATEMENT = {
  customer: { id: 12, name: 'Ferme Démo Compte' },
  from: null,
  to: '2026-11-03',
  openingBalance: '0.00',
  lines: [
    { date: '2026-10-01', at: '2026-10-01T09:00:00Z', kind: 'invoice', documentId: 40, paymentId: null, number: 'INV-2026-000040', method: null, reference: null, debit: '100.00', credit: '0.00', balance: '100.00' },
    { date: '2026-10-05', at: '2026-10-05T09:00:00Z', kind: 'payment', documentId: null, paymentId: 6, number: null, method: 'cheque', reference: 'CHQ-6', debit: '0.00', credit: '20.00', balance: '80.00' },
  ],
  totalDebit: '100.00',
  totalCredit: '20.00',
  closingBalance: '80.00',
  uninvoicedDeliveryNotes: '0.00',
}

function serveAccount() {
  server.use(
    http.get('/api/customers/12', () => HttpResponse.json(CUSTOMER)),
    http.get('/api/customers/12/prices', () => HttpResponse.json([])),
    http.get('/api/customers/12/balance', () =>
      HttpResponse.json({ balance: '115.00', uninvoicedDeliveryNotes: '0.00', creditLimit: null, availableCredit: null }),
    ),
    http.get('/api/customers/12/open-invoices', () => HttpResponse.json(OPEN)),
    http.get('/api/customers/12/statement', () => HttpResponse.json(STATEMENT)),
  )
}

async function openAccount() {
  renderApp('/customers/12')
  await userEvent.click(await screen.findByRole('tab', { name: 'Compte' }))
}

describe('customer account (frontend.md §4.6, W8, ADR-134, ADR-135)', () => {
  it('shows the open invoices, the advances and the statement with its running balance', async () => {
    serveMe(READER)
    serveAccount()
    await openAccount()

    const rows = await screen.findAllByTestId('open-invoice')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('INV-2026-000040')
    expect(screen.getByTestId('advance')).toHaveTextContent('15,00')
    const lines = await screen.findAllByTestId('statement-line')
    expect(lines[1]).toHaveTextContent('Paiement')
    expect(lines[1]).toHaveTextContent('CHQ-6')
    expect(screen.getByTestId('closing-balance')).toHaveTextContent('80,00')
    expect(screen.getByRole('link', { name: 'Relevé (PDF)' })).toHaveAttribute('href', '/api/customers/12/statement?format=pdf')
    expect(screen.queryByRole('button', { name: 'Enregistrer un paiement' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Annuler le paiement' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Imputer' })).toBeNull()
  })

  it('records a payment split oldest first by the server, with an idempotency key and no allocations sent', async () => {
    serveMe(ACCOUNTANT)
    serveAccount()
    let sent: { body: unknown; key: string | null } | undefined
    server.use(
      http.post('/api/payments/customers', async ({ request }) => {
        sent = { body: await request.json(), key: request.headers.get('Idempotency-Key') }
        return HttpResponse.json({ id: 9 }, { status: 201 })
      }),
    )
    await openAccount()
    await userEvent.click(await screen.findByRole('button', { name: 'Enregistrer un paiement' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Montant'), '100')
    await userEvent.type(within(dialog).getByLabelText('Référence (n° de chèque ou de virement)'), 'CHQ-9')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(sent).toBeDefined())
    expect(sent?.body).toEqual({ customerId: 12, method: 'cheque', amount: '100', reference: 'CHQ-9' })
    expect(sent?.key).toMatch(/^[0-9a-f-]{36}$/)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('sends the invoices chosen by hand and shows the server refusal on the field or as a message', async () => {
    serveMe(ACCOUNTANT)
    serveAccount()
    const bodies: unknown[] = []
    server.use(
      http.post('/api/payments/customers', async ({ request }) => {
        bodies.push(await request.json())
        return bodies.length === 1
          ? problem(422, 'PAYMENT_ALLOCATION_EXCEEDED', { context: { invoiceId: 41, open: '50.00', requested: '60.00' } })
          : problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'allocations[0].amount', code: 'POSITIVE', message: '' }] })
      }),
    )
    await openAccount()
    await userEvent.click(await screen.findByRole('button', { name: 'Enregistrer un paiement' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.selectOptions(within(dialog).getByLabelText('Mode de paiement'), 'cash')
    expect(within(dialog).queryByLabelText('Référence (n° de chèque ou de virement)')).toBeNull()
    await userEvent.type(within(dialog).getByLabelText('Montant'), '60')
    await userEvent.click(within(dialog).getByLabelText('Choisir les factures'))
    await userEvent.type(within(dialog).getByLabelText('INV-2026-000041 (reste dû 50,00 DA)'), '60')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    expect(await within(dialog).findByRole('alert')).toBeInTheDocument()
    expect(bodies[0]).toEqual({ customerId: 12, method: 'cash', amount: '60', reference: null, allocations: [{ invoiceId: 41, amount: '60' }] })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))
    expect(await within(dialog).findByText('La valeur doit être supérieure à zéro.')).toBeInTheDocument()
  })

  it('applies an advance and reverses a payment with its reason', async () => {
    serveMe(ACCOUNTANT)
    serveAccount()
    let applied: unknown
    let reversed: { url: string; body: unknown } | undefined
    server.use(
      http.post('/api/payments/customers/7/allocations', async ({ request }) => {
        applied = await request.json()
        return HttpResponse.json({ id: 7 })
      }),
      http.post('/api/payments/customers/:id/reverse', async ({ request }) => {
        reversed = { url: request.url, body: await request.json() }
        return HttpResponse.json({ id: 6 })
      }),
    )
    await openAccount()
    await userEvent.click(await screen.findByRole('button', { name: 'Imputer' }))
    let dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('INV-2026-000040 (reste dû 80,00 DA)'), '15')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Imputer' }))
    await waitFor(() => expect(applied).toEqual({ allocations: [{ invoiceId: 40, amount: '15' }] }))

    await userEvent.click(await screen.findByRole('button', { name: 'Annuler le paiement' }))
    dialog = screen.getByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Annuler le paiement' }))
    expect(within(dialog).getByText('Ce champ est obligatoire.')).toBeInTheDocument()
    await userEvent.type(within(dialog).getByLabelText('Motif'), 'Chèque rejeté')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Annuler le paiement' }))
    await waitFor(() => expect(reversed?.body).toEqual({ reason: 'Chèque rejeté' }))
    expect(reversed?.url).toContain('/api/payments/customers/6/reverse')
  })

  it('has no Account tab without payment.read or statement.read', async () => {
    serveMe(PHARMACIST)
    serveAccount()
    server.use(http.get('/api/customers', () => HttpResponse.json({ content: [], page: { number: 0, size: 20, totalElements: 0, totalPages: 0 } })))
    server.use(http.get('/api/customer-categories', () => HttpResponse.json([])))
    renderApp('/customers/12')
    expect(await screen.findByRole('tab', { name: 'Aperçu' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Compte' })).toBeNull()
  })
})

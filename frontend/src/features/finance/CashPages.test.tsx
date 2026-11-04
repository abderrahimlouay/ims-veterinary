import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const MANAGER = aUser({ permissions: ['cash.open', 'cash.manage'] })
const CASHIER = aUser({ permissions: ['cash.open', 'sale.create'] })

function aSession(overrides: Record<string, unknown> = {}) {
  return {
    id: 21,
    cashierId: MANAGER.user.id,
    status: 'open',
    openedAt: '2026-11-03T08:00:00Z',
    closedAt: null,
    openingAmount: '100.00',
    expectedAmount: '145.50',
    countedAmount: null,
    varianceAmount: null,
    version: 0,
    ...overrides,
  }
}

const OTHER = aSession({ id: 20, cashierId: 77, status: 'closed', closedAt: '2026-11-02T18:00:00Z', expectedAmount: '300.00', countedAmount: '295.00', varianceAmount: '-5.00' })

const REPORT = {
  session: aSession(),
  closedBy: null,
  openingAmount: '100.00',
  bySource: [
    { source: 'sales', in: '65.50', out: '0.00' },
    { source: 'supplier_payments', in: '0.00', out: '20.00' },
    { source: 'manual', in: '0.00', out: '0.00' },
  ],
  nonCash: [
    { kind: 'customer_payment', id: 31, reversal: false, method: 'cheque', reference: 'CHQ-31', amount: '200.00', direction: 'in', at: '2026-11-03T10:00:00Z' },
    { kind: 'supplier_payment', id: 9, reversal: true, method: 'bank_transfer', reference: 'VIR-9', amount: '80.00', direction: 'in', at: '2026-11-03T11:00:00Z' },
  ],
  expected: '145.50',
  counted: null,
  variance: null,
}

function serveSessions() {
  server.use(
    http.get('/api/cash-sessions/current', () => HttpResponse.json(aSession())),
    http.get('/api/cash-sessions', () =>
      HttpResponse.json({ content: [aSession(), OTHER], page: { number: 0, size: 20, totalElements: 2, totalPages: 1 } }),
    ),
    http.get('/api/cash-sessions/21/report', () => HttpResponse.json(REPORT)),
  )
}

describe('cash sessions (frontend.md §4.9, ADR-136, BR-CASH-003)', () => {
  it('lists every session with its cashier, amounts and variance', async () => {
    serveMe(MANAGER)
    serveSessions()
    renderApp('/cash-sessions')

    const rows = await screen.findAllByTestId('cash-session-row')
    expect(rows[0]).toHaveTextContent('Vous')
    expect(rows[1]).toHaveTextContent('Utilisateur n° 77')
    expect(rows[1]).toHaveTextContent('-5,00')
    expect(within(rows[0]).getByRole('button', { name: 'Fermer la session' })).toBeInTheDocument()
    expect(within(rows[1]).queryByRole('button')).toBeNull()
    expect(screen.getByTestId('own-session')).toHaveTextContent('145,50')
  })

  it('posts a manual movement with a mandatory label and an idempotency key, and shows a refusal', async () => {
    serveMe(MANAGER)
    serveSessions()
    const sent: { body: unknown; key: string | null }[] = []
    server.use(
      http.post('/api/cash-sessions/21/movements', async ({ request }) => {
        sent.push({ body: await request.json(), key: request.headers.get('Idempotency-Key') })
        return sent.length === 1 ? problem(422, 'CASH_INSUFFICIENT') : HttpResponse.json(aSession({ expectedAmount: '125.50' }), { status: 201 })
      }),
    )
    renderApp('/cash-sessions')
    await userEvent.click(await screen.findByRole('button', { name: 'Mouvement manuel' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Montant'), '500')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))
    expect(within(dialog).getByText('Ce champ est obligatoire.')).toBeInTheDocument()
    expect(sent).toHaveLength(0)

    await userEvent.type(within(dialog).getByLabelText('Libellé'), 'Dépôt banque')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Pas assez d’espèces')
    await userEvent.clear(within(dialog).getByLabelText('Montant'))
    await userEvent.type(within(dialog).getByLabelText('Montant'), '20')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(sent[1].body).toEqual({ direction: 'out', amount: '20', label: 'Dépôt banque' })
    expect(sent[0].key).toMatch(/^[0-9a-f-]{36}$/)
    expect(sent[1].key).toBe(sent[0].key)
  })

  it('closes another cashier’s open session as a manager', async () => {
    serveMe(MANAGER)
    let closed: { url: string; body: unknown } | undefined
    server.use(
      http.get('/api/cash-sessions/current', () => new HttpResponse(null, { status: 204 })),
      http.get('/api/cash-sessions', () =>
        HttpResponse.json({ content: [aSession({ id: 22, cashierId: 77 })], page: { number: 0, size: 20, totalElements: 1, totalPages: 1 } }),
      ),
      http.post('/api/cash-sessions/:id/close', async ({ request }) => {
        closed = { url: request.url, body: await request.json() }
        return HttpResponse.json(aSession({ id: 22, status: 'closed' }))
      }),
    )
    renderApp('/cash-sessions')
    expect(await screen.findByText('Vous n’avez pas de session ouverte.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mouvement manuel' })).toBeNull()
    await userEvent.click(await screen.findByRole('button', { name: 'Fermer la session' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.type(within(dialog).getByLabelText('Montant compté'), '140')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Fermer la session' }))
    await waitFor(() => expect(closed?.body).toEqual({ countedAmount: '140' }))
    expect(closed?.url).toContain('/api/cash-sessions/22/close')
  })

  it('shows the report by source with the cheques and transfers apart', async () => {
    serveMe(MANAGER)
    serveSessions()
    renderApp('/cash-sessions/21')

    expect(await screen.findByTestId('report-expected')).toHaveTextContent('145,50')
    expect(screen.getByRole('link', { name: 'Rapport (PDF)' })).toHaveAttribute('href', '/api/cash-sessions/21/report?format=pdf')
    expect(screen.getByTestId('source-sales')).toHaveTextContent('65,50')
    expect(screen.getByTestId('source-supplier_payments')).toHaveTextContent('20,00')
    const nonCash = screen.getAllByTestId('non-cash')
    expect(nonCash[0]).toHaveTextContent('CHQ-31')
    expect(nonCash[1]).toHaveTextContent('Paiement fournisseur · annulation')
    expect(screen.getByRole('button', { name: 'Mouvement manuel' })).toBeInTheDocument()
  })

  it('lets a cashier read the report of their own session, without manual movements', async () => {
    serveMe(CASHIER)
    server.use(http.get('/api/cash-sessions/21/report', () => HttpResponse.json({ ...REPORT, session: aSession({ cashierId: CASHIER.user.id }) })))
    renderApp('/cash-sessions/21')

    expect(await screen.findByTestId('report-expected')).toHaveTextContent('145,50')
    expect(screen.queryByRole('button', { name: 'Mouvement manuel' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Fermer la session' })).toBeInTheDocument()
  })
})

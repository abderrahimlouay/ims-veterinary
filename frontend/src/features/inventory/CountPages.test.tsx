import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const COUNTER = aUser({ permissions: ['inventory.count', 'lot.read', 'product.read'] })
const VALIDATOR = aUser({ permissions: ['inventory.count', 'inventory.validate', 'lot.read', 'product.read'] })
const PRODUCT = { id: 5, sku: 'AMX-10', commercialName: 'Amoxival 500', baseUnit: 'tablet' }
const AMINA = { id: 7, fullName: 'Amina Benali' }

function aLine(overrides: Record<string, unknown> = {}) {
  return {
    id: 101,
    product: PRODUCT,
    lot: { id: 21, lotNumber: 'L1', expiryDate: '2027-05-31' },
    foundLot: null,
    theoreticalQty: '10.000',
    countedQty: null,
    countedAt: null,
    countedBy: null,
    balanceAtCount: null,
    varianceQty: null,
    movementsSinceCount: null,
    currentBalance: '10.000',
    regularized: false,
    adjustmentMovementId: null,
    ...overrides,
  }
}

function aCount(lines: unknown[], overrides: Record<string, unknown> = {}) {
  return {
    id: 12,
    type: 'cycle',
    status: 'open',
    scope: { categoryId: null, productIds: [5] },
    notes: null,
    startedAt: '2026-10-20T08:00:00Z',
    startedBy: AMINA,
    closedAt: null,
    closedBy: null,
    version: 0,
    lines,
    ...overrides,
  }
}

const L2 = aLine({ id: 102, lot: { id: 22, lotNumber: 'L2', expiryDate: '2027-06-30' }, theoreticalQty: '5.000', currentBalance: '5.000' })

describe('inventory counts (frontend.md §4.4, W5)', () => {
  it('opens a cycle count on a product list and shows its grid', async () => {
    serveMe(COUNTER)
    let sent: unknown
    server.use(
      http.get('/api/categories', () => HttpResponse.json([])),
      http.get('/api/products', () =>
        HttpResponse.json({ content: [{ ...PRODUCT, category: null, salePrice: '1.0000', archived: false, sellableQtyBase: '15.000', totalQtyBase: '15.000', stockStatus: 'in_stock', nearestExpiry: null }], page: { number: 0, size: 10, totalElements: 1, totalPages: 1 } }),
      ),
      http.post('/api/inventory/counts', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json(aCount([aLine(), L2]), { status: 201 })
      }),
      http.get('/api/inventory/counts/12', () => HttpResponse.json(aCount([aLine(), L2]))),
    )
    const router = renderApp('/inventory-counts/new')

    await userEvent.click(await screen.findByLabelText(/Tournant/))
    await userEvent.click(screen.getByLabelText('Une liste de produits'))
    await userEvent.click(screen.getByRole('button', { name: "Ouvrir l'inventaire" }))
    expect(screen.getByLabelText('Rechercher un produit')).toHaveAccessibleDescription('Choisissez une catégorie ou au moins un produit.')
    await userEvent.type(screen.getByLabelText('Rechercher un produit'), 'amox')
    await userEvent.click(await screen.findByRole('button', { name: 'Ajouter Amoxival 500' }))
    await userEvent.click(screen.getByRole('button', { name: "Ouvrir l'inventaire" }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/inventory-counts/12'))
    expect(sent).toEqual({ type: 'cycle', productIds: [5] })
    expect(await screen.findByLabelText('Quantité comptée — Amoxival 500 — L1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Régulariser' })).toBeDisabled()
    expect((await axe.run(document.body)).violations).toEqual([])
  })

  it('saves a counted cell with Enter, moves to the next one and shows the variance against the balance when counted', async () => {
    serveMe(COUNTER)
    const puts: { url: string; body: unknown }[] = []
    let counted = false
    server.use(
      http.get('/api/inventory/counts/12', () =>
        HttpResponse.json(
          aCount([
            counted
              ? aLine({ countedQty: '9.000', balanceAtCount: '10.000', varianceQty: '-1.000', movementsSinceCount: '-2.000', currentBalance: '8.000', countedBy: AMINA, countedAt: '2026-10-20T08:00:00Z' })
              : aLine(),
            L2,
          ]),
        ),
      ),
      http.put('/api/inventory/counts/12/lines/:lineId', async ({ request, params }) => {
        puts.push({ url: String(params.lineId), body: await request.json() })
        counted = true
        return HttpResponse.json(aLine())
      }),
    )
    renderApp('/inventory-counts/12')

    const cell = await screen.findByLabelText('Quantité comptée — Amoxival 500 — L1')
    await userEvent.type(cell, '9{Enter}')

    await waitFor(() => expect(screen.getByLabelText('Quantité comptée — Amoxival 500 — L2')).toHaveFocus())
    expect(puts).toEqual([{ url: '101', body: { countedQty: '9' } }])
    const row = (await screen.findByText('Comptée par Amina Benali')).closest('tr')!
    expect(within(row).getByText('−1,000')).toBeInTheDocument()
    expect(within(row).getByText('−2,000')).toBeInTheDocument()
    expect(within(row).getByText('8,000')).toBeInTheDocument()
  })

  it('shows a refused quantity on its line', async () => {
    serveMe(COUNTER)
    server.use(
      http.get('/api/inventory/counts/12', () => HttpResponse.json(aCount([aLine()]))),
      http.put('/api/inventory/counts/12/lines/101', () =>
        problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'countedQty', code: 'WHOLE_NUMBER', message: 'x' }] }),
      ),
    )
    renderApp('/inventory-counts/12')

    await userEvent.type(await screen.findByLabelText('Quantité comptée — Amoxival 500 — L1'), '1.5{Enter}')

    expect(await screen.findByText(/unités entières|nombre entier/i)).toBeInTheDocument()
  })

  it('adds a lot unknown to the system with a month expiry', async () => {
    serveMe(COUNTER)
    let sent: unknown
    server.use(
      http.get('/api/inventory/counts/12', () => HttpResponse.json(aCount([aLine()]))),
      http.get('/api/products', () =>
        HttpResponse.json({ content: [{ ...PRODUCT, category: null, salePrice: '1.0000', archived: false, sellableQtyBase: '0.000', totalQtyBase: '0.000', stockStatus: 'out', nearestExpiry: null }], page: { number: 0, size: 10, totalElements: 1, totalPages: 1 } }),
      ),
      http.post('/api/inventory/counts/12/lines', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json(aLine({ id: 103, lot: null, foundLot: { lotNumber: 'NEW-1', expiryDate: '2027-05-31', unitCost: '0.5000' } }), { status: 201 })
      }),
    )
    renderApp('/inventory-counts/12')

    await userEvent.click(await screen.findByRole('button', { name: 'Ajouter un lot' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.click(within(dialog).getByLabelText('Un lot inconnu du système'))
    await userEvent.type(within(dialog).getByLabelText('Rechercher un produit'), 'amox')
    await userEvent.click(await within(dialog).findByRole('button', { name: 'Choisir Amoxival 500' }))
    await userEvent.type(within(dialog).getByLabelText('N° de lot'), 'NEW-1')
    await userEvent.type(within(dialog).getByLabelText('Date de péremption'), '05/2027')
    await userEvent.type(within(dialog).getByLabelText('Coût unitaire (tablet)'), '0,5')
    await userEvent.type(within(dialog).getByLabelText('Compté'), '4')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Ajouter la ligne' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(sent).toEqual({ productId: 5, lotNumber: 'NEW-1', expiryDate: '2027-05-31', unitCost: '0.5', countedQty: '4' })
  })

  it('confirms the net quantity per product and names the refused line', async () => {
    serveMe(VALIDATOR)
    const lines = [
      aLine({ countedQty: '8.000', balanceAtCount: '10.000', varianceQty: '-2.000', movementsSinceCount: '0.000', countedBy: AMINA }),
      aLine({ ...L2, countedQty: '6.000', balanceAtCount: '5.000', varianceQty: '1.000', movementsSinceCount: '-4.000', currentBalance: '1.000', countedBy: AMINA }),
    ]
    server.use(
      http.get('/api/inventory/counts/12', () => HttpResponse.json(aCount(lines))),
      http.post('/api/inventory/counts/12/regularize', () =>
        problem(422, 'STOCK_INSUFFICIENT', { context: { lineId: 101, lotId: 21, available: '1.000', variance: '-2.000' } }),
      ),
    )
    renderApp('/inventory-counts/12')

    await userEvent.click(await screen.findByRole('button', { name: 'Régulariser' }))
    const dialog = screen.getByRole('dialog', { name: "Régulariser l'inventaire n° 12" })
    expect(within(dialog).getByText('−1,000 tablet')).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Régulariser et clôturer' }))

    expect(await within(dialog).findByText(/la ligne « Amoxival 500 — L1 » rendrait le solde du lot négatif/)).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Annuler' }))
    expect(screen.getByRole('link', { name: 'L1' }).closest('tr')).toHaveClass('bg-danger/10')
  })

  it('shows a closed count read-only', async () => {
    serveMe(VALIDATOR)
    server.use(
      http.get('/api/inventory/counts/12', () =>
        HttpResponse.json(
          aCount([aLine({ countedQty: '8.000', balanceAtCount: '10.000', varianceQty: '-2.000', regularized: true, adjustmentMovementId: 90, countedBy: AMINA })], {
            status: 'closed',
            closedAt: '2026-10-20T09:00:00Z',
            closedBy: AMINA,
          }),
        ),
      ),
    )
    renderApp('/inventory-counts/12')

    expect(await screen.findByText('Régularisée')).toBeInTheDocument()
    expect(screen.queryByLabelText(/Quantité comptée/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Régulariser' })).not.toBeInTheDocument()
    expect(screen.getByText(/clôturé le/)).toBeInTheDocument()
  })

  it('lists counts with their progress', async () => {
    serveMe(COUNTER)
    server.use(
      http.get('/api/inventory/counts', () =>
        HttpResponse.json({
          content: [{ id: 12, type: 'full', status: 'open', startedAt: '2026-10-20T08:00:00Z', startedBy: AMINA, closedAt: null, lineCount: 40, countedCount: 15 }],
          page: { number: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      ),
    )
    renderApp('/inventory-counts')

    const row = (await screen.findByRole('link', { name: 'Inventaire n° 12' })).closest('tr')!
    expect(row).toHaveTextContent('Complet')
    expect(row).toHaveTextContent('15 / 40')
  })
})

describe('reorder proposals (W10)', () => {
  const PROPOSAL = {
    id: 3,
    product: { ...PRODUCT, divisible: false },
    currentQty: '4.000',
    suggestedQty: '46.000',
    reorderPoint: '10.000',
    maxStock: '50.000',
    status: 'pending',
    createdAt: '2026-10-20T05:00:00Z',
    updatedAt: '2026-10-20T05:00:00Z',
    dismissedAt: null,
    dismissedBy: null,
  }

  it('generates now, reports incomplete thresholds and dismisses a proposal', async () => {
    serveMe(aUser({ permissions: ['reorder.read', 'reorder.manage', 'product.read'] }))
    let dismissed = false
    server.use(
      http.get('/api/inventory/reorder-proposals', ({ request }) =>
        HttpResponse.json({
          content: dismissed && new URL(request.url).searchParams.get('status') === 'pending' ? [] : [PROPOSAL],
          page: { number: 0, size: 20, totalElements: 1, totalPages: 1 },
        }),
      ),
      http.post('/api/inventory/reorder-proposals/generate', () =>
        HttpResponse.json({
          created: 1,
          updated: 0,
          thresholdsIncomplete: [{ id: 9, sku: 'NOMAX', commercialName: 'Sans maximum' }],
          thresholdsInconsistent: [{ id: 10, sku: 'MAXLOW', commercialName: 'Maximum trop bas' }],
        }),
      ),
      http.post('/api/inventory/reorder-proposals/3/dismiss', () => {
        dismissed = true
        return HttpResponse.json({ ...PROPOSAL, status: 'dismissed' })
      }),
    )
    renderApp('/reorder')

    const row = (await screen.findByRole('link', { name: 'Amoxival 500' })).closest('tr')!
    expect(row).toHaveTextContent('46,000 tablet')
    await userEvent.click(screen.getByRole('button', { name: 'Générer maintenant' }))
    expect(await screen.findByText('1 proposition(s) créée(s), 0 mise(s) à jour.', { exact: false })).toBeInTheDocument()
    expect(screen.getByText(/Seuils incomplets \(stock maximum absent\)/)).toContainElement(screen.getByRole('link', { name: 'Sans maximum' }))
    expect(screen.getByRole('link', { name: 'Sans maximum' })).toHaveAttribute('href', '/products/9')
    expect(screen.getByText(/Seuils incohérents/)).toContainElement(screen.getByRole('link', { name: 'Maximum trop bas' }))
    await userEvent.click(screen.getByRole('button', { name: 'Écarter la proposition pour Amoxival 500' }))
    expect(await screen.findByText('Aucune proposition.')).toBeInTheDocument()
  })

  it('is read-only without reorder.manage', async () => {
    serveMe(aUser({ permissions: ['reorder.read'] }))
    server.use(
      http.get('/api/inventory/reorder-proposals', () =>
        HttpResponse.json({ content: [PROPOSAL], page: { number: 0, size: 20, totalElements: 1, totalPages: 1 } }),
      ),
    )
    renderApp('/reorder')

    await screen.findByRole('link', { name: 'Amoxival 500' })
    expect(screen.queryByRole('button', { name: 'Générer maintenant' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Écarter/ })).not.toBeInTheDocument()
  })
})

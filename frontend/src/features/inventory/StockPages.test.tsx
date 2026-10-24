import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, PUBLIC_SETTINGS, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const STOCK_KEEPER = aUser({ permissions: ['stock.read', 'stock.adjust', 'lot.read', 'product.read'] })
const READER = aUser({ permissions: ['stock.read', 'lot.read', 'product.read'] })
const AUDITOR = aUser({ permissions: ['audit.read'] })
const PRODUCT = { id: 5, sku: 'AMX-10', commercialName: 'Amoxival 500', baseUnit: 'tablet' }

function page(content: unknown[], totalElements = content.length) {
  return { content, page: { number: 0, size: 20, totalElements, totalPages: 1 } }
}

function aLevel(overrides: Record<string, unknown> = {}) {
  return {
    product: { ...PRODUCT, archived: false },
    totalQty: '30.000',
    sellableQty: '8.000',
    minStock: '10.000',
    maxStock: '50.000',
    reorderPoint: '12.000',
    status: 'low',
    belowReorder: true,
    nearestSellableExpiry: '2027-05-31',
    ...overrides,
  }
}

function aMovement(overrides: Record<string, unknown> = {}) {
  return {
    id: 90,
    lot: { id: 21, lotNumber: 'L2407A', expiryDate: '2027-05-31' },
    product: PRODUCT,
    type: 'adjustment_breakage',
    quantity: '-2.000',
    referenceType: 'adjustment',
    referenceId: null,
    reason: 'Broken vial',
    user: { id: 7, fullName: 'Amina Benali' },
    createdAt: '2026-10-20T08:00:00Z',
    ...overrides,
  }
}

function aLotDetail(overrides: Record<string, unknown> = {}) {
  return {
    id: 21,
    product: PRODUCT,
    lotNumber: 'L2407A',
    expiryDate: '2027-05-31',
    quantityOnHand: '120.000',
    status: 'blocked',
    alertLevel: null,
    daysLeft: 223,
    unitCost: '0.5000',
    blockedReason: 'Damaged',
    blockedBy: 3,
    blockedByName: 'Karim',
    blockedAt: '2026-10-19T08:00:00Z',
    receivedAt: '2026-10-01T08:00:00Z',
    sellable: false,
    ...overrides,
  }
}

describe('stock levels and ledger (frontend.md §4.3)', () => {
  it('lists sellable and total stock with the status from the server, filters kept in the URL', async () => {
    serveMe(READER)
    const queries: URLSearchParams[] = []
    server.use(
      http.get('/api/inventory/stock', ({ request }) => {
        queries.push(new URL(request.url).searchParams)
        return HttpResponse.json(page([aLevel()]))
      }),
    )
    const router = renderApp('/stock?status=low')

    const row = (await screen.findByRole('link', { name: 'Amoxival 500' })).closest('tr')!
    expect(row).toHaveTextContent('8,000 tablet')
    expect(row).toHaveTextContent('30,000')
    expect(within(row).getByText('Stock bas')).toBeInTheDocument()
    expect(within(row).getByText('Sous le seuil de réapprovisionnement')).toBeInTheDocument()
    expect(queries.at(-1)?.get('status')).toBe('low')
    await userEvent.click(screen.getByLabelText('Sous le seuil de réapprovisionnement seulement'))
    await waitFor(() => expect(queries.at(-1)?.get('belowReorder')).toBe('true'))
    expect(router.state.location.search).toContain('belowReorder=true')
    expect(screen.queryByRole('link', { name: 'Nouvel ajustement' })).not.toBeInTheDocument()
    expect((await axe.run(document.body)).violations).toEqual([])
  })

  it('shows signed movements with a sign, not by colour alone', async () => {
    serveMe(READER)
    const queries: URLSearchParams[] = []
    server.use(
      http.get('/api/inventory/movements', ({ request }) => {
        queries.push(new URL(request.url).searchParams)
        return HttpResponse.json(page([aMovement(), aMovement({ id: 91, type: 'opening_stock', quantity: '10.000', reason: null })]))
      }),
    )
    renderApp('/stock?view=movements&lotId=21')

    expect(await screen.findByText('−2,000 tablet')).toBeInTheDocument()
    expect(screen.getByText('+10,000 tablet')).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'Casse' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: "Stock d'ouverture" })).toBeInTheDocument()
    expect(queries.at(-1)?.get('lotId')).toBe('21')
    expect(screen.getByRole('tab', { name: 'Mouvements' })).toHaveAttribute('aria-selected', 'true')
  })
})

describe('manual adjustment (ADR-085)', () => {
  it('previews the balance after and sends the adjustment, then shows the lot', async () => {
    serveMe(STOCK_KEEPER)
    let sent: unknown
    server.use(
      http.get('/api/lots/21', () => HttpResponse.json(aLotDetail())),
      http.post('/api/inventory/adjustments', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json(aMovement(), { status: 201 })
      }),
      http.get('/api/inventory/movements', () => HttpResponse.json(page([aMovement()]))),
    )
    const router = renderApp('/stock/adjustments/new?lotId=21')

    await screen.findByText('Bloqué')
    await userEvent.selectOptions(screen.getByLabelText("Type d'ajustement"), 'adjustment_expiry')
    await userEvent.type(screen.getByLabelText('Quantité retirée (tablet)'), '2,5')
    expect(screen.getByLabelText('Quantité retirée (tablet)')).toHaveAccessibleDescription('Solde après : 117,500 tablet')
    await userEvent.type(screen.getByLabelText('Motif'), 'Expired in the fridge')
    await userEvent.click(screen.getByRole('button', { name: "Enregistrer l'ajustement" }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/lots/21'))
    expect(sent).toEqual({ lotId: 21, type: 'adjustment_expiry', quantity: '2.5', reason: 'Expired in the fridge' })
    expect(await screen.findByText('Ajustement enregistré.')).toBeInTheDocument()
    expect(await screen.findByText('−2,000 tablet')).toBeInTheDocument()
  })

  it('requires a reason and shows what the server refuses', async () => {
    serveMe(STOCK_KEEPER)
    server.use(
      http.get('/api/lots/21', () => HttpResponse.json(aLotDetail({ quantityOnHand: '1.000' }))),
      http.post('/api/inventory/adjustments', () => problem(422, 'STOCK_INSUFFICIENT', { context: { lotId: 21, available: '1.000' } })),
    )
    renderApp('/stock/adjustments/new?lotId=21')

    await userEvent.type(await screen.findByLabelText('Quantité retirée (tablet)'), '3')
    expect(screen.getByText('La quantité dépasse le solde du lot ; le serveur refusera.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: "Enregistrer l'ajustement" }))
    expect(screen.getByLabelText('Motif')).toHaveAccessibleDescription('Ce champ est obligatoire.')
    await userEvent.type(screen.getByLabelText('Motif'), 'Theft')
    await userEvent.click(screen.getByRole('button', { name: "Enregistrer l'ajustement" }))
    expect(await screen.findByText('Stock insuffisant : 1,000 tablet disponibles sur le lot.')).toBeInTheDocument()
  })

  it('lets the user pick a lot first', async () => {
    serveMe(STOCK_KEEPER)
    server.use(
      http.get('/api/lots', () =>
        HttpResponse.json(page([{ ...aLotDetail(), status: 'active', alertLevel: null, daysLeft: 223 }])),
      ),
      http.get('/api/lots/21', () => HttpResponse.json(aLotDetail())),
    )
    const router = renderApp('/stock/adjustments/new')

    await userEvent.click(await screen.findByRole('button', { name: 'Choisir le lot L2407A' }))
    await waitFor(() => expect(router.state.location.search).toBe('?lotId=21'))
    expect(await screen.findByLabelText("Type d'ajustement")).toBeInTheDocument()
  })
})

describe('opening stock (ADR-082…084)', () => {
  function serveProduct() {
    server.use(
      http.get('/api/products', () =>
        HttpResponse.json(page([{ ...PRODUCT, category: null, salePrice: '1.0000', archived: false, sellableQtyBase: '0.000', totalQtyBase: '0.000', stockStatus: 'out', nearestExpiry: null }])),
      ),
      http.get('/api/products/5', () =>
        HttpResponse.json({ ...PRODUCT, purchasePrice: '0.5000', divisible: false, category: null, salePrice: '1.0000', archived: false }),
      ),
      http.get('/api/products/5/units', () =>
        HttpResponse.json([{ id: 8, name: 'Box', factorToBase: '30.0000', salePrice: null, effectiveSalePrice: '30.00', sellable: true, version: 0 }]),
      ),
    )
  }

  it('enters a lot with a month expiry, a unit and the prefilled cost', async () => {
    serveMe(STOCK_KEEPER)
    serveProduct()
    let sent: unknown
    server.use(
      http.post('/api/inventory/opening-stock', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json(
          { lot: aLotDetail({ id: 30, lotNumber: 'OPEN-1', quantityOnHand: '60.000', status: 'active' }), movement: aMovement() },
          { status: 201 },
        )
      }),
    )
    renderApp('/stock/opening-stock/new')

    await userEvent.type(await screen.findByLabelText('Rechercher un produit'), 'amox')
    await userEvent.click(await screen.findByRole('button', { name: 'Choisir Amoxival 500' }))
    await userEvent.type(await screen.findByLabelText('N° de lot'), 'OPEN-1')
    await userEvent.type(screen.getByLabelText('Date de péremption'), '05/2027')
    expect(screen.getByLabelText('Date de péremption')).toHaveAccessibleDescription('31 mai 2027')
    await userEvent.type(screen.getByLabelText('Quantité'), '2')
    await userEvent.selectOptions(screen.getByLabelText('Unité'), '8')
    expect(screen.getByLabelText('Coût unitaire (tablet)')).toHaveValue('0.5000')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText(/Lot OPEN-1 enregistré : 60,000 tablet en stock/)).toBeInTheDocument()
    expect(sent).toEqual({ productId: 5, lotNumber: 'OPEN-1', expiryDate: '2027-05-31', quantity: '2', unitId: 8, unitCost: '0.5000' })
    expect(screen.getByLabelText('N° de lot')).toHaveValue('')
  })

  it('warns about an expired lot and places the server field errors', async () => {
    serveMe(STOCK_KEEPER)
    serveProduct()
    server.use(
      http.post('/api/inventory/opening-stock', () =>
        problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'unitCost', code: 'SCALE', message: 'x' }] }),
      ),
    )
    renderApp('/stock/opening-stock/new')

    await userEvent.type(await screen.findByLabelText('Rechercher un produit'), 'amox')
    await userEvent.click(await screen.findByRole('button', { name: 'Choisir Amoxival 500' }))
    await userEvent.type(await screen.findByLabelText('N° de lot'), 'OLD-1')
    await userEvent.type(screen.getByLabelText('Date de péremption'), '31/01/2020')
    expect(screen.getByText(/Ce lot est déjà périmé/)).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Quantité'), '1')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() =>
      expect(screen.getByLabelText('Coût unitaire (tablet)')).toHaveAccessibleDescription(
        "Prérempli avec le prix d'achat du produit. Trop de décimales.",
      ),
    )
  })

  it('explains that a closed period goes through an inventory count', async () => {
    serveMe(STOCK_KEEPER)
    server.use(http.get('/api/settings/public', () => HttpResponse.json({ ...PUBLIC_SETTINGS, openingStockOpen: false })))
    renderApp('/stock/opening-stock/new')

    expect(await screen.findByText(/passe par un inventaire/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Rechercher un produit')).not.toBeInTheDocument()
  })
})

describe('integrity banner (ADR-086)', () => {
  const RUN = {
    id: 4,
    startedAt: '2026-10-20T01:00:00Z',
    finishedAt: '2026-10-20T01:00:02Z',
    lotsChecked: 120,
    mismatchCount: 1,
    mismatches: [{ lotId: 21, lotNumber: 'L2407A', productId: 5, balance: '10.000', ledgerSum: '8.000' }],
    triggeredBy: null,
  }

  it('shows the mismatches to audit.read holders and disappears after a clean run', async () => {
    serveMe(AUDITOR)
    server.use(
      http.get('/api/inventory/integrity-check', () => HttpResponse.json(RUN)),
      http.post('/api/inventory/integrity-check', () =>
        HttpResponse.json({ ...RUN, id: 5, mismatchCount: 0, mismatches: [], triggeredBy: { id: 7, fullName: 'Amina Benali' } }),
      ),
    )
    renderApp('/')

    const banner = await screen.findByText(/1 lot\(s\) dont le solde diffère/)
    await userEvent.click(within(banner.closest('[role="alert"]') as HTMLElement).getByRole('link', { name: 'Voir le rapport' }))
    expect(await screen.findByRole('link', { name: 'L2407A' })).toBeInTheDocument()
    expect(screen.getByText(/le contrôle nocturne : 120 lots vérifiés/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Lancer le contrôle' }))

    expect(await screen.findByText('Chaque solde est égal à la somme de ses mouvements.')).toBeInTheDocument()
    expect(screen.queryByText(/dont le solde diffère/)).not.toBeInTheDocument()
  })

  it('is never loaded for other users', async () => {
    serveMe(READER)
    let asked = false
    server.use(
      http.get('/api/inventory/integrity-check', () => {
        asked = true
        return HttpResponse.json(RUN)
      }),
      http.get('/api/lots/expiry-alerts', () =>
        HttpResponse.json({ expired: [], critical: [], warning: [], notice: [], thresholds: { notice: 60, warning: 30, critical: 15 }, businessDate: '2026-10-20' }),
      ),
      http.get('/api/inventory/stock', () => HttpResponse.json(page([], 0))),
    )
    renderApp('/')

    expect(await screen.findByText('Aucun produit en rupture ni en stock bas.')).toBeInTheDocument()
    expect(asked).toBe(false)
  })
})

describe('low-stock widget (ADR-089)', () => {
  it('counts the products out of stock and low, each opening the matching list', async () => {
    serveMe(aUser({ permissions: ['stock.read'] }))
    server.use(
      http.get('/api/inventory/stock', ({ request }) => {
        const status = new URL(request.url).searchParams.get('status')
        return HttpResponse.json(page([aLevel()], status === 'out' ? 3 : 7))
      }),
    )
    renderApp('/')

    const out = await screen.findByRole('link', { name: /Rupture/ })
    expect(out).toHaveTextContent('3')
    expect(out).toHaveAttribute('href', '/stock?status=out')
    expect(screen.getByRole('link', { name: /Stock bas/ })).toHaveTextContent('7')
  })
})

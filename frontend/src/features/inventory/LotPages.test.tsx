import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const PHARMACIST = aUser({ permissions: ['lot.read', 'lot.block', 'lot.unblock', 'lot.recall', 'product.read'] })
const SELLER = aUser({ permissions: ['lot.read', 'product.read'] })
const PRODUCT = { id: 5, sku: 'AMX-10', commercialName: 'Amoxival 500', baseUnit: 'tablet' }

function aLot(overrides: Record<string, unknown> = {}) {
  return {
    id: 21,
    product: PRODUCT,
    lotNumber: 'L2407A',
    expiryDate: '2026-11-01',
    quantityOnHand: '120.000',
    status: 'active',
    alertLevel: 'critical',
    daysLeft: 12,
    ...overrides,
  }
}

function aDetail(overrides: Record<string, unknown> = {}) {
  return {
    ...aLot(),
    unitCost: '0.5000',
    blockedReason: null,
    blockedBy: null,
    blockedAt: null,
    receivedAt: '2026-10-01T08:00:00Z',
    sellable: true,
    ...overrides,
  }
}

function page(content: unknown[]) {
  return { content, page: { number: 0, size: 20, totalElements: content.length, totalPages: 1 } }
}

describe('lot list (frontend.md §4.2)', () => {
  it('lists lots with the status and alert badges returned by the API', async () => {
    serveMe(SELLER)
    server.use(
      http.get('/api/lots', () =>
        HttpResponse.json(page([aLot(), aLot({ id: 22, lotNumber: 'L-OLD', status: 'expired', alertLevel: 'expired', daysLeft: -2 })])),
      ),
    )
    renderApp('/lots')

    const row = (await screen.findByRole('link', { name: 'L2407A' })).closest('tr')!
    expect(row).toHaveTextContent('Amoxival 500')
    expect(within(row).getByText('Critique')).toBeInTheDocument()
    expect(within(row).getByText('Actif')).toBeInTheDocument()
    expect(within(row).getByText(/120,000/)).toBeInTheDocument()
    const old = screen.getByRole('link', { name: 'L-OLD' }).closest('tr')!
    expect(within(old).getAllByText('Périmé')).toHaveLength(2)
  })

  it('explains that lots appear with opening stock and receipts', async () => {
    serveMe(SELLER)
    server.use(http.get('/api/lots', () => HttpResponse.json(page([]))))
    renderApp('/lots')

    expect(await screen.findByText(/stock d'ouverture et les réceptions/)).toBeInTheDocument()
  })

  it('keeps the filters in the URL and sends them', async () => {
    serveMe(SELLER)
    const queries: URLSearchParams[] = []
    server.use(
      http.get('/api/lots', ({ request }) => {
        queries.push(new URL(request.url).searchParams)
        return HttpResponse.json(page([]))
      }),
    )
    const router = renderApp('/lots?expiringWithinDays=30&hasStock=true')

    await waitFor(() => expect(queries.at(-1)?.get('expiringWithinDays')).toBe('30'))
    expect(queries.at(-1)?.get('hasStock')).toBe('true')
    await userEvent.selectOptions(await screen.findByLabelText('Statut'), 'blocked')
    await waitFor(() => expect(queries.at(-1)?.get('status')).toBe('blocked'))
    expect(router.state.location.search).toContain('status=blocked')
    expect(await screen.findByText('Aucun lot ne correspond aux filtres.')).toBeInTheDocument()
  })

  it('shows a load failure with its reference', async () => {
    serveMe(SELLER)
    server.use(http.get('/api/lots', () => problem(500, 'INTERNAL_ERROR')))
    renderApp('/lots')

    expect(await screen.findByRole('alert')).toHaveTextContent('trace-1')
  })
})

describe('lot detail and actions (ADR-060, W6)', () => {
  it('blocks a lot with a mandatory reason', async () => {
    serveMe(PHARMACIST)
    let lot = aDetail()
    let body: unknown
    server.use(
      http.get('/api/lots/21', () => HttpResponse.json(lot)),
      http.post('/api/lots/21/block', async ({ request }) => {
        body = await request.json()
        lot = aDetail({ status: 'blocked', blockedReason: 'Damaged', blockedAt: '2026-10-20T08:00:00Z', sellable: false })
        return HttpResponse.json(lot)
      }),
    )
    renderApp('/lots/21')

    expect(await screen.findByRole('heading', { level: 1, name: /L2407A/ })).toBeInTheDocument()
    expect(screen.getByText('Vendable')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Débloquer' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Bloquer' }))
    const dialog = screen.getByRole('dialog', { name: 'Bloquer le lot L2407A' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmer' }))
    expect(within(dialog).getByText('Ce champ est obligatoire.')).toBeInTheDocument()
    expect(body).toBeUndefined()

    await userEvent.type(within(dialog).getByLabelText('Motif'), 'Damaged')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmer' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(body).toEqual({ reason: 'Damaged' })
    expect(await screen.findByText('Damaged')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Débloquer' })).toBeInTheDocument()
  })

  it('shows a refused transition in the dialog', async () => {
    serveMe(PHARMACIST)
    server.use(
      http.get('/api/lots/21', () => HttpResponse.json(aDetail())),
      http.post('/api/lots/21/block', () => problem(409, 'INVALID_STATE_TRANSITION')),
    )
    renderApp('/lots/21')

    await userEvent.click(await screen.findByRole('button', { name: 'Bloquer' }))
    await userEvent.type(screen.getByLabelText('Motif'), 'Damaged')
    await userEvent.click(screen.getByRole('button', { name: 'Confirmer' }))

    expect(await within(screen.getByRole('dialog')).findByRole('alert')).toHaveTextContent("n'est pas possible")
  })

  it('recalls a lot and shows that no sales exist yet', async () => {
    serveMe(PHARMACIST)
    let lot = aDetail({ status: 'blocked', sellable: false, blockedReason: 'Suspicion' })
    server.use(
      http.get('/api/lots/21', () => HttpResponse.json(lot)),
      http.post('/api/lots/21/recall', () => {
        lot = aDetail({ status: 'recalled', sellable: false, blockedReason: 'Notice', blockedAt: '2026-10-20T08:00:00Z' })
        return HttpResponse.json({
          lot: { id: 21, status: 'recalled', blockedAt: '2026-10-20T08:00:00Z', blockedBy: 7 },
          buyers: [],
          anonymousSales: [],
          remainingStockBase: '120.000',
        })
      }),
    )
    renderApp('/lots/21')

    await userEvent.click(await screen.findByRole('button', { name: 'Rappeler' }))
    await userEvent.type(screen.getByLabelText('Motif'), 'Notice')
    await userEvent.click(screen.getByRole('button', { name: 'Confirmer' }))

    expect(await screen.findByText(/Aucune vente n'existe encore/)).toBeInTheDocument()
    expect(screen.getByText(/Stock restant : 120,000 tablet/)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Débloquer' })).not.toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Bloquer' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Rappeler' })).not.toBeInTheDocument()
  })

  it('reopens the recall report of a recalled lot', async () => {
    serveMe(PHARMACIST)
    server.use(
      http.get('/api/lots/21', () => HttpResponse.json(aDetail({ status: 'recalled', sellable: false }))),
      http.get('/api/lots/21/recall-report', () =>
        HttpResponse.json({ lot: { id: 21, status: 'recalled', blockedAt: null, blockedBy: 7 }, buyers: [], anonymousSales: [], remainingStockBase: '3.000' }),
      ),
    )
    renderApp('/lots/21')

    await userEvent.click(await screen.findByRole('button', { name: 'Rapport de rappel' }))

    expect(await screen.findByText(/Stock restant : 3,000 tablet/)).toBeInTheDocument()
  })

  it('offers no action without the permissions', async () => {
    serveMe(SELLER)
    server.use(http.get('/api/lots/21', () => HttpResponse.json(aDetail({ status: 'blocked', sellable: false }))))
    renderApp('/lots/21')

    expect(await screen.findByRole('heading', { level: 1, name: /L2407A/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Débloquer' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Rappeler' })).not.toBeInTheDocument()
  })

  it('shows an unknown lot as not found', async () => {
    serveMe(SELLER)
    server.use(http.get('/api/lots/99', () => problem(404, 'NOT_FOUND')))
    renderApp('/lots/99')

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})

describe('lots tab of a product', () => {
  const PRODUCT_DETAIL = {
    id: 5, sku: 'AMX-10', commercialName: 'Amoxival 500', category: null, dosageForm: null, manufacturer: null,
    baseUnit: 'tablet', divisible: false, vatRate: '9.00', purchasePrice: '0.0000', salePrice: '0.0125',
    minStock: '0.000', maxStock: null, reorderPoint: '0.000', archived: false, ingredients: [], targetSpecies: [],
    withdrawalPeriods: [], createdAt: '2026-10-20T08:00:00Z', updatedAt: '2026-10-20T08:00:00Z', version: 0,
  }

  function serveProduct(lots: unknown[]) {
    server.use(
      http.get('/api/products/5', () => HttpResponse.json(PRODUCT_DETAIL)),
      http.get('/api/categories', () => HttpResponse.json([])),
      http.get('/api/dosage-forms', () => HttpResponse.json([])),
      http.get('/api/manufacturers', () => HttpResponse.json([])),
      http.get('/api/products/5/lots', () => HttpResponse.json(lots)),
    )
  }

  it('lists the lots of the product in FEFO order', async () => {
    serveMe(SELLER)
    serveProduct([aLot({ id: 1, lotNumber: 'FIRST' }), aLot({ id: 2, lotNumber: 'SECOND', alertLevel: null })])
    renderApp('/products/5')

    await userEvent.click(await screen.findByRole('tab', { name: 'Lots' }))
    const links = await screen.findAllByRole('link', { name: /FIRST|SECOND/ })
    expect(links.map((l) => l.textContent)).toEqual(['FIRST', 'SECOND'])
  })

  it('is hidden without lot.read', async () => {
    serveMe(aUser({ permissions: ['product.read'] }))
    serveProduct([])
    renderApp('/products/5')

    await screen.findByRole('tab', { name: 'Général' })
    expect(screen.queryByRole('tab', { name: 'Lots' })).not.toBeInTheDocument()
  })
})

describe('dashboard expiry widget', () => {
  const ALERTS = {
    expired: [aLot({ status: 'expired', alertLevel: 'expired' })],
    critical: [aLot(), aLot({ id: 23 })],
    warning: [],
    notice: [aLot({ id: 24, alertLevel: 'notice' })],
    thresholds: { notice: 60, warning: 30, critical: 15 },
    businessDate: '2026-10-20',
  }

  it('labels each level with its threshold from the settings and links to exactly that level', async () => {
    serveMe(SELLER)
    server.use(http.get('/api/lots/expiry-alerts', () => HttpResponse.json(ALERTS)))
    renderApp('/')

    expect(await screen.findByRole('link', { name: 'Critique (≤ 15 jours) 2' })).toHaveAttribute(
      'href',
      '/lots?alertLevel=critical&hasStock=true',
    )
    expect(screen.getByRole('link', { name: 'Périmés 1' })).toHaveAttribute('href', '/lots?alertLevel=expired&hasStock=true')
    expect(screen.getByRole('link', { name: 'Attention (≤ 30 jours) 0' })).toHaveAttribute('href', '/lots?alertLevel=warning&hasStock=true')
    expect(screen.getByRole('link', { name: 'À surveiller (≤ 60 jours) 1' })).toBeInTheDocument()
  })

  it('opens a list whose size is the count shown', async () => {
    serveMe(SELLER)
    const sent: URLSearchParams[] = []
    server.use(
      http.get('/api/lots/expiry-alerts', () => HttpResponse.json(ALERTS)),
      http.get('/api/lots', ({ request }) => {
        const params = new URL(request.url).searchParams
        sent.push(params)
        const level = params.get('alertLevel') as keyof typeof ALERTS
        return HttpResponse.json(page(ALERTS[level] as unknown[]))
      }),
    )
    renderApp('/')

    await userEvent.click(await screen.findByRole('link', { name: 'Critique (≤ 15 jours) 2' }))

    await waitFor(() => expect(screen.getAllByRole('link', { name: 'L2407A' })).toHaveLength(2))
    expect(sent.at(-1)?.get('alertLevel')).toBe('critical')
    expect(sent.at(-1)?.get('hasStock')).toBe('true')
    expect(screen.getByLabelText("Niveau d'alerte")).toHaveValue('critical')
  })

  it('has an empty state when no lot is near expiry', async () => {
    serveMe(SELLER)
    server.use(
      http.get('/api/lots/expiry-alerts', () =>
        HttpResponse.json({ ...ALERTS, expired: [], critical: [], notice: [] }),
      ),
    )
    renderApp('/')

    expect(await screen.findByText('Aucun lot proche de la péremption.')).toBeInTheDocument()
  })

  it('is not shown without lot.read', async () => {
    serveMe(aUser({ permissions: [] }))
    renderApp('/')

    expect(await screen.findByRole('heading', { name: 'Tableau de bord' })).toBeInTheDocument()
    expect(screen.queryByText('Péremption des lots')).not.toBeInTheDocument()
  })
})

describe('accessibility of the lot screens', () => {
  it('has no axe violations on the list and on the detail with its dialog open', async () => {
    serveMe(PHARMACIST)
    server.use(
      http.get('/api/lots', () => HttpResponse.json(page([aLot()]))),
      http.get('/api/lots/21', () => HttpResponse.json(aDetail())),
    )
    const router = renderApp('/lots')
    await screen.findByRole('link', { name: 'L2407A' })
    expect((await axe.run(document.body)).violations).toEqual([])

    await router.navigate('/lots/21')
    await userEvent.click(await screen.findByRole('button', { name: 'Bloquer' }))
    expect((await axe.run(document.body)).violations).toEqual([])
  })
})

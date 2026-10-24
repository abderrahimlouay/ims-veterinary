import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import axe from 'axe-core'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const BUYER = aUser({
  permissions: ['supplier.read', 'supplier.manage', 'purchase.read', 'purchase.create', 'purchase.validate', 'product.read'],
})
const READER = aUser({ permissions: ['supplier.read', 'purchase.read'] })

function page(content: unknown[]) {
  return { content, page: { number: 0, size: 20, totalElements: content.length, totalPages: 1 } }
}

function aSupplier(overrides: Record<string, unknown> = {}) {
  return {
    id: 3,
    name: 'Biopharm',
    kind: 'wholesaler',
    taxId: null,
    phone: '021 00 00 00',
    email: null,
    address: null,
    paymentTermsDays: 30,
    active: true,
    createdAt: '2026-10-20T08:00:00Z',
    updatedAt: '2026-10-20T08:00:00Z',
    version: 0,
    ...overrides,
  }
}

const PRODUCT = { id: 5, sku: 'AMX-10', commercialName: 'Amoxival 500', baseUnit: 'tablet' }

function anOrder(overrides: Record<string, unknown> = {}) {
  return {
    id: 41,
    number: 'PO-2026-000041',
    supplier: { id: 3, name: 'Biopharm', active: true },
    status: 'draft',
    orderDate: '2026-10-20',
    expectedDate: null,
    lines: [
      {
        id: 120,
        product: PRODUCT,
        unit: null,
        quantity: '10.000',
        quantityBase: '10.000',
        receivedBase: '0.000',
        outstandingBase: '10.000',
        unitPrice: '0.3350',
        discountPct: '0.00',
        vatRate: '9.00',
        lineHt: '3.35',
        lineVat: '0.30',
      },
    ],
    totalHt: '3.35',
    totalVat: '0.30',
    totalTtc: '3.65',
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
    version: 0,
    ...overrides,
  }
}

describe('suppliers (frontend.md §4.5)', () => {
  it('lists the active suppliers and shows a duplicate name on the name field', async () => {
    serveMe(BUYER)
    let query = ''
    server.use(
      http.get('/api/suppliers', ({ request }) => {
        query = new URL(request.url).search
        return HttpResponse.json(page([aSupplier()]))
      }),
      http.post('/api/suppliers', () => problem(409, 'DUPLICATE_RESOURCE', { context: { field: 'name' } })),
    )
    renderApp('/suppliers')

    expect(await screen.findByRole('link', { name: 'Biopharm' })).toHaveAttribute('href', '/suppliers/3')
    expect(query).toContain('active=true')
    await userEvent.click(screen.getByRole('link', { name: 'Nouveau fournisseur' }))
    await userEvent.type(await screen.findByLabelText('Nom'), 'biopharm')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText('Cette valeur est déjà utilisée.')).toBeInTheDocument()
  })

  it('deactivates a supplier and strikes through the prices of a cancelled receipt', async () => {
    serveMe(BUYER)
    let deactivated = false
    server.use(
      http.get('/api/suppliers/3', () => HttpResponse.json(aSupplier({ active: !deactivated }))),
      http.post('/api/suppliers/3/deactivate', () => {
        deactivated = true
        return HttpResponse.json(aSupplier({ active: false }))
      }),
      http.get('/api/suppliers/3/prices', () =>
        HttpResponse.json(
          page([
            { id: 2, product: PRODUCT, unitPrice: '0.3300', discountPct: '0.00', validFrom: '2026-10-20', createdAt: '2026-10-20T08:00:00Z', createdBy: { id: 7, fullName: 'Amina Benali' }, receiptId: 9, cancelled: true },
            { id: 1, product: PRODUCT, unitPrice: '0.3000', discountPct: '5.00', validFrom: '2026-10-01', createdAt: '2026-10-01T08:00:00Z', createdBy: { id: 7, fullName: 'Amina Benali' }, receiptId: null, cancelled: false },
          ]),
        ),
      ),
    )
    renderApp('/suppliers/3')

    const cancelled = (await screen.findByText('Réception annulée')).closest('tr')
    expect(cancelled).toHaveClass('line-through')
    expect(screen.getByText('Saisie manuelle').closest('tr')).not.toHaveClass('line-through')
    await userEvent.click(screen.getByRole('button', { name: 'Désactiver' }))

    expect(await screen.findByRole('button', { name: 'Réactiver' })).toBeInTheDocument()
  })
})

describe('purchase orders (frontend.md §4.5)', () => {
  it('prefills the price of a new line and sends no amount', async () => {
    serveMe(BUYER)
    let sent: Record<string, unknown> | undefined
    server.use(
      http.get('/api/suppliers', () => HttpResponse.json(page([aSupplier()]))),
      http.get('/api/products', () => HttpResponse.json(page([{ ...PRODUCT, category: null, salePrice: '1.00', archived: false, sellableQtyBase: '0.000', totalQtyBase: '0.000', stockStatus: 'out', nearestExpiry: null }]))),
      http.get('/api/products/5/units', () => HttpResponse.json([])),
      http.get('/api/purchase-orders/prefill', () => HttpResponse.json({ unitPrice: '0.3350', discountPct: '0.00', vatRate: '9.00', source: 'history' })),
      http.post('/api/purchase-orders', async ({ request }) => {
        sent = (await request.json()) as Record<string, unknown>
        return HttpResponse.json(anOrder(), { status: 201 })
      }),
      http.get('/api/purchase-orders/41', () => HttpResponse.json(anOrder())),
    )
    const router = renderApp('/purchase-orders/new')

    await screen.findByRole('option', { name: 'Biopharm' })
    await userEvent.selectOptions(screen.getByLabelText('Fournisseur'), '3')
    await userEvent.type(screen.getByLabelText('Ajouter un produit'), 'amox')
    await userEvent.click(await screen.findByRole('button', { name: 'Choisir Amoxival 500' }))
    await waitFor(() => expect(screen.getByLabelText('Prix unitaire')).toHaveValue('0.3350'))
    await userEvent.type(screen.getByLabelText('Quantité'), '10')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer le brouillon' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/purchase-orders/41'))
    expect(sent).toEqual({
      supplierId: 3,
      expectedDate: null,
      lines: [{ productId: 5, unitId: null, quantity: '10', unitPrice: '0.3350', discountPct: '0.00', vatRate: '9.00' }],
    })
    expect(await screen.findByText('TTC 3,65 DA')).toBeInTheDocument()
  })

  it('offers the actions of the status and asks a reason before cancelling', async () => {
    serveMe(BUYER)
    let cancelled: unknown
    server.use(
      http.get('/api/purchase-orders/41', () => HttpResponse.json(anOrder())),
      http.post('/api/purchase-orders/41/cancel', async ({ request }) => {
        cancelled = await request.json()
        return HttpResponse.json(anOrder({ status: 'cancelled', cancelReason: 'Ordered twice' }))
      }),
    )
    renderApp('/purchase-orders/41')

    expect(await screen.findByRole('button', { name: 'Valider' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Clôturer' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Modifier' })).toHaveAttribute('href', '/purchase-orders/41/edit')
    await userEvent.click(screen.getByRole('button', { name: 'Annuler le bon' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmer' }))
    expect(within(dialog).getByText('Ce champ est obligatoire.')).toBeInTheDocument()
    await userEvent.type(within(dialog).getByLabelText('Motif'), 'Ordered twice')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Confirmer' }))

    await waitFor(() => expect(cancelled).toEqual({ reason: 'Ordered twice' }))
    const results = await axe.run(document.body)
    expect(results.violations).toEqual([])
  })

  it('shows no action to a reader and hides purchasing from the menu without the permissions', async () => {
    serveMe(READER)
    server.use(http.get('/api/purchase-orders/41', () => HttpResponse.json(anOrder({ status: 'partially_received' }))))
    renderApp('/purchase-orders/41')

    expect(await screen.findByRole('heading', { name: 'PO-2026-000041' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Clôturer' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Modifier' })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Bons de commande' })).toBeInTheDocument()
  })
})

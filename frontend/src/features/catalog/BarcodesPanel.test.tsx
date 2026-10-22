import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const EDITOR = aUser({ permissions: ['product.read', 'product.update'] })

const PRODUCT = {
  id: 5,
  sku: 'AMX-10',
  commercialName: 'Amoxival 500',
  category: null,
  dosageForm: null,
  manufacturer: null,
  baseUnit: 'tablet',
  divisible: false,
  vatRate: '9.00',
  purchasePrice: '0.0000',
  salePrice: '0.0125',
  minStock: '0.000',
  maxStock: null,
  reorderPoint: '0.000',
  archived: false,
  ingredients: [],
  targetSpecies: [],
  withdrawalPeriods: [],
  createdAt: '2026-10-20T08:00:00Z',
  updatedAt: '2026-10-20T08:00:00Z',
  version: 0,
}
const BOX = { id: 8, name: 'Box', factorToBase: '30.0000', salePrice: null, effectiveSalePrice: '0.3750', sellable: true, version: 0 }
const BASE_CODE = { id: 1, code: '4006381333931', codeType: 'EAN13', unit: null }
const BOX_CODE = { id: 2, code: 'BOX-AMX', codeType: 'CODE128', unit: BOX }

function serveProduct(barcodes: unknown[]) {
  server.use(
    http.get('/api/products/5', () => HttpResponse.json(PRODUCT)),
    http.get('/api/categories', () => HttpResponse.json([])),
    http.get('/api/dosage-forms', () => HttpResponse.json([])),
    http.get('/api/manufacturers', () => HttpResponse.json([])),
    http.get('/api/products/5/units', () => HttpResponse.json([BOX])),
    http.get('/api/products/5/barcodes', () => HttpResponse.json(barcodes)),
  )
}

async function openBarcodes() {
  await userEvent.click(await screen.findByRole('tab', { name: 'Codes-barres' }))
}

describe('barcodes tab (FR-PROD-003)', () => {
  it('lists barcodes with their unit or the base unit', async () => {
    serveMe(EDITOR)
    serveProduct([BASE_CODE, BOX_CODE])
    renderApp('/products/5')
    await openBarcodes()

    const base = (await screen.findByRole('cell', { name: '4006381333931' })).closest('tr')!
    expect(within(base).getByText('Unité de base (tablet)')).toBeInTheDocument()
    expect(within(base).getByText('EAN-13')).toBeInTheDocument()
    const box = screen.getByRole('cell', { name: 'BOX-AMX' }).closest('tr')!
    expect(within(box).getByText('Box')).toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    serveMe(EDITOR)
    serveProduct([])
    renderApp('/products/5')
    await openBarcodes()

    expect(await screen.findByText('Aucun code-barres pour ce produit.')).toBeInTheDocument()
  })

  it('adds a barcode for a unit, with a hint per type and server errors on the code', async () => {
    serveMe(EDITOR)
    serveProduct([])
    const bodies: unknown[] = []
    server.use(
      http.post('/api/products/5/barcodes', async ({ request }) => {
        bodies.push(await request.json())
        if (bodies.length === 1) {
          return problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'code', code: 'CHECK_DIGIT', message: 'x' }] })
        }
        if (bodies.length === 2) {
          return problem(409, 'BARCODE_DUPLICATE', { context: { field: 'code' } })
        }
        return HttpResponse.json(BOX_CODE, { status: 201 })
      }),
    )
    renderApp('/products/5')
    await openBarcodes()

    expect(await screen.findByText(/13 chiffres/)).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Code'), '4006381333932')
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter le code' }))
    expect(await screen.findByText('Le chiffre de contrôle EAN-13 est incorrect.')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Ajouter le code' }))
    expect(await screen.findByText('Ce code-barres appartient déjà à un produit.')).toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText('Type'), 'CODE128')
    expect(screen.getByText(/ASCII/)).toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText('Code'))
    await userEvent.type(screen.getByLabelText('Code'), 'BOX-AMX')
    await userEvent.selectOptions(screen.getByLabelText('Unité'), '8')
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter le code' }))
    await screen.findByText('Modifications enregistrées.')
    expect(bodies[2]).toEqual({ code: 'BOX-AMX', codeType: 'CODE128', unitId: 8 })
  })

  it('removes a barcode', async () => {
    serveMe(EDITOR)
    let barcodes = [BASE_CODE, BOX_CODE]
    serveProduct([])
    let deleted = ''
    server.use(
      http.get('/api/products/5/barcodes', () => HttpResponse.json(barcodes)),
      http.delete('/api/products/5/barcodes/:id', ({ params }) => {
        deleted = String(params.id)
        barcodes = barcodes.filter((b) => String(b.id) !== deleted)
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderApp('/products/5')
    await openBarcodes()

    await userEvent.click(await screen.findByRole('button', { name: 'Retirer BOX-AMX' }))

    await waitFor(() => expect(screen.queryByRole('cell', { name: 'BOX-AMX' })).not.toBeInTheDocument())
    expect(deleted).toBe('2')
  })

  it('is read-only for a reader', async () => {
    serveMe(aUser({ permissions: ['product.read'] }))
    serveProduct([BOX_CODE])
    renderApp('/products/5')
    await openBarcodes()

    expect(await screen.findByRole('cell', { name: 'BOX-AMX' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retirer BOX-AMX' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Code')).not.toBeInTheDocument()
  })

  it('shows a load failure with its reference', async () => {
    serveMe(EDITOR)
    serveProduct([])
    server.use(http.get('/api/products/5/barcodes', () => problem(500, 'INTERNAL_ERROR')))
    renderApp('/products/5')
    await openBarcodes()

    expect(await screen.findByRole('alert')).toHaveTextContent('trace-1')
  })
})

describe('scanning in the product search', () => {
  it('opens the product whose barcode is scanned (Enter-terminated)', async () => {
    serveMe(EDITOR)
    serveProduct([])
    server.use(
      http.get('/api/animal-species', () => HttpResponse.json([])),
      http.get('/api/active-ingredients', () => HttpResponse.json([])),
      http.get('/api/products', ({ request }) => {
        const barcode = new URL(request.url).searchParams.get('barcode')
        const content = barcode === 'AMX/10' || barcode === null ? [PRODUCT] : []
        return HttpResponse.json({ content, page: { number: 0, size: 20, totalElements: content.length, totalPages: 1 } })
      }),
    )
    const router = renderApp('/products')

    await userEvent.type(await screen.findByLabelText('Rechercher'), 'AMX/10{Enter}')

    await waitFor(() => expect(router.state.location.pathname).toBe('/products/5'))
  })

  it('keeps the text search when the typed value is no barcode', async () => {
    serveMe(EDITOR)
    serveProduct([])
    const barcodes: string[] = []
    server.use(
      http.get('/api/animal-species', () => HttpResponse.json([])),
      http.get('/api/active-ingredients', () => HttpResponse.json([])),
      http.get('/api/products', ({ request }) => {
        const barcode = new URL(request.url).searchParams.get('barcode')
        if (barcode !== null) {
          barcodes.push(barcode)
        }
        const content = barcode === null ? [PRODUCT] : []
        return HttpResponse.json({ content, page: { number: 0, size: 20, totalElements: content.length, totalPages: 1 } })
      }),
    )
    const router = renderApp('/products')

    await userEvent.type(await screen.findByLabelText('Rechercher'), 'amox{Enter}')

    await waitFor(() => expect(barcodes).toEqual(['amox']))
    await waitFor(() => expect(router.state.location.search).toBe('?q=amox'))
    expect(router.state.location.pathname).toBe('/products')
  })
})

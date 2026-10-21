import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const PHARMACIST = aUser({ permissions: ['product.read', 'product.create', 'product.update', 'product.archive'] })
const ANTIBIOTIC = { id: 1, name: 'Antibiotic', code: 'ANTIBIOTIC', country: null }

function aProduct(overrides: Record<string, unknown> = {}) {
  return {
    id: 5,
    sku: 'AMX-10',
    commercialName: 'Amoxival 500',
    category: ANTIBIOTIC,
    dosageForm: null,
    manufacturer: null,
    baseUnit: 'tablet',
    divisible: false,
    vatRate: '9.00',
    purchasePrice: '0.0000',
    salePrice: '0.0125',
    minStock: '10.000',
    maxStock: null,
    reorderPoint: '0.000',
    archived: false,
    createdAt: '2026-10-20T08:00:00Z',
    updatedAt: '2026-10-20T08:00:00Z',
    version: 0,
    ...overrides,
  }
}

function serveReferences() {
  server.use(
    http.get('/api/categories', () => HttpResponse.json([ANTIBIOTIC])),
    http.get('/api/dosage-forms', () => HttpResponse.json([])),
    http.get('/api/manufacturers', () => HttpResponse.json([])),
  )
}

describe('product pages', () => {
  it('lists products with translated categories and four-decimal prices', async () => {
    serveMe(PHARMACIST)
    const queries: string[] = []
    server.use(
      http.get('/api/products', ({ request }) => {
        queries.push(new URL(request.url).search)
        return HttpResponse.json({ content: [aProduct()], page: { number: 0, size: 20, totalElements: 1, totalPages: 1 } })
      }),
    )
    renderApp('/products')

    const row = (await screen.findByRole('link', { name: 'Amoxival 500' })).closest('tr')!
    expect(within(row).getByText('Antibiotique')).toBeInTheDocument()
    expect(within(row).getByText(/0,0125/)).toBeInTheDocument()
    await userEvent.click(screen.getByLabelText('Afficher les produits archivés'))
    await screen.findByRole('link', { name: 'Amoxival 500' })
    expect(queries.at(-1)).toContain('includeArchived=true')
  })

  it('creates a product with the default VAT and shows server field errors', async () => {
    serveMe(PHARMACIST)
    serveReferences()
    const bodies: Record<string, unknown>[] = []
    server.use(
      http.post('/api/products', async ({ request }) => {
        bodies.push((await request.json()) as Record<string, unknown>)
        return bodies.length === 1
          ? problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'minStock', code: 'WHOLE_NUMBER', message: 'x' }] })
          : HttpResponse.json(aProduct({ id: 8 }), { status: 201 })
      }),
      http.get('/api/products/8', () => HttpResponse.json(aProduct({ id: 8 }))),
    )
    const router = renderApp('/products/new')

    await userEvent.type(await screen.findByLabelText('SKU'), 'AMX-10')
    await userEvent.type(screen.getByLabelText('Nom commercial'), 'Amoxival 500')
    await userEvent.selectOptions(screen.getByLabelText('Catégorie thérapeutique'), '1')
    await userEvent.type(screen.getByLabelText('Unité de base'), 'tablet')
    await userEvent.clear(screen.getByLabelText('Stock minimum'))
    await userEvent.type(screen.getByLabelText('Stock minimum'), '2.5')
    expect(screen.getByLabelText('TVA (%)')).toHaveValue('9.00')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText('Ce produit se compte en unités entières.')).toBeInTheDocument()
    expect(bodies[0]).toMatchObject({ sku: 'AMX-10', categoryId: 1, divisible: false, vatRate: '9.00', minStock: '2.5' })

    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Amoxival 500' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/products/8')
  })

  it('is read-only for a reader and offers no archive', async () => {
    serveMe(aUser({ permissions: ['product.read'] }))
    serveReferences()
    server.use(http.get('/api/products/5', () => HttpResponse.json(aProduct())))
    renderApp('/products/5')

    expect(await screen.findByLabelText('SKU')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Enregistrer' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Archiver' })).not.toBeInTheDocument()
  })

  it('offers to reload after a concurrent change and archives', async () => {
    serveMe(PHARMACIST)
    serveReferences()
    let version = 0
    server.use(
      http.get('/api/products/5', () => HttpResponse.json(aProduct({ version, commercialName: version ? 'Changed elsewhere' : 'Amoxival 500' }))),
      http.put('/api/products/5', () => {
        version = 1
        return problem(409, 'CONCURRENT_MODIFICATION')
      }),
      http.post('/api/products/5/archive', () => HttpResponse.json(aProduct({ archived: true, version: 1 }))),
    )
    renderApp('/products/5')

    await userEvent.click(await screen.findByRole('button', { name: 'Enregistrer' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Recharger la dernière version' }))
    expect(await screen.findByDisplayValue('Changed elsewhere')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Archiver' }))
    expect(await screen.findByRole('button', { name: 'Restaurer' })).toBeInTheDocument()
  })
})

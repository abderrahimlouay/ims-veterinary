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
    ingredients: [],
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

  it('sends prices only with price.edit (BR-PROD-006)', async () => {
    serveMe(PHARMACIST)
    serveReferences()
    let body: Record<string, unknown> = {}
    server.use(
      http.get('/api/products/5', () => HttpResponse.json(aProduct())),
      http.put('/api/products/5', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return HttpResponse.json(aProduct({ version: 1 }))
      }),
    )
    renderApp('/products/5')

    expect(await screen.findByLabelText('Prix de vente (unité de base)')).toBeDisabled()
    expect(screen.getByText(/Les prix ne sont modifiables/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await screen.findByText('Modifications enregistrées.')
    expect(body).not.toHaveProperty('salePrice')
    expect(body).not.toHaveProperty('purchasePrice')
  })

  it('edits a price with up to four decimals when allowed', async () => {
    serveMe(aUser({ permissions: [...PHARMACIST.permissions, 'price.edit'] }))
    serveReferences()
    let body: Record<string, unknown> = {}
    server.use(
      http.get('/api/products/5', () => HttpResponse.json(aProduct())),
      http.put('/api/products/5', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return HttpResponse.json(aProduct({ version: 1, salePrice: '0.0150' }))
      }),
    )
    renderApp('/products/5')

    const sale = await screen.findByLabelText('Prix de vente (unité de base)')
    await userEvent.clear(sale)
    await userEvent.type(sale, '0.00001')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(await screen.findByText('Trop de décimales.')).toBeInTheDocument()

    await userEvent.clear(sale)
    await userEvent.type(sale, '0.015')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await screen.findByText('Modifications enregistrées.')
    expect(body).toMatchObject({ salePrice: '0.015', purchasePrice: '0.0000' })
  })

  it('edits the active ingredients as a set and lists substitutes', async () => {
    serveMe(PHARMACIST)
    serveReferences()
    let saved: unknown
    server.use(
      http.get('/api/products/5', () =>
        HttpResponse.json(aProduct({ ingredients: [{ ingredient: { id: 3, name: 'Amoxicilline', code: null, country: null }, strength: '500 mg' }] })),
      ),
      http.get('/api/active-ingredients', () =>
        HttpResponse.json([
          { id: 3, name: 'Amoxicilline', code: null, country: null },
          { id: 4, name: 'Acide clavulanique', code: null, country: null },
        ]),
      ),
      http.put('/api/products/5/ingredients', async ({ request }) => {
        saved = await request.json()
        return HttpResponse.json([])
      }),
      http.get('/api/products/5/substitutes', () =>
        HttpResponse.json([{ id: 9, sku: 'CLAV-1', commercialName: 'Clavamox', category: null, baseUnit: 'tablet', salePrice: '1.5000', archived: false }]),
      ),
    )
    renderApp('/products/5')

    await userEvent.click(await screen.findByRole('tab', { name: 'Principes actifs' }))
    expect(screen.getByLabelText('Dosage 1')).toHaveValue('500 mg')
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter un principe actif' }))
    await userEvent.selectOptions(screen.getByLabelText('Principe actif 2'), '4')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await screen.findByText('Modifications enregistrées.')
    expect(saved).toEqual([
      { ingredientId: 3, strength: '500 mg' },
      { ingredientId: 4, strength: null },
    ])

    await userEvent.click(screen.getByRole('tab', { name: 'Substituts' }))
    expect(await screen.findByRole('link', { name: 'Clavamox' })).toHaveAttribute('href', '/products/9')
  })

  it('reports a duplicated ingredient', async () => {
    serveMe(PHARMACIST)
    serveReferences()
    server.use(
      http.get('/api/products/5', () => HttpResponse.json(aProduct())),
      http.get('/api/active-ingredients', () => HttpResponse.json([{ id: 3, name: 'Amoxicilline', code: null, country: null }])),
      http.put('/api/products/5/ingredients', () =>
        problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'ingredients', code: 'DUPLICATE', message: 'x' }] }),
      ),
    )
    renderApp('/products/5')

    await userEvent.click(await screen.findByRole('tab', { name: 'Principes actifs' }))
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter un principe actif' }))
    await userEvent.selectOptions(screen.getByLabelText('Principe actif 1'), '3')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Un principe actif figure deux fois.')
  })
})

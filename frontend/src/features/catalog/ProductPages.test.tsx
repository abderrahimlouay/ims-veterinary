import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const PHARMACIST = aUser({ permissions: ['product.read', 'product.create', 'product.update', 'product.archive'] })
const ANTIBIOTIC = { id: 1, name: 'Antibiotic', code: 'ANTIBIOTIC' }

function aProduct(overrides: Record<string, unknown> = {}) {
  return {
    id: 5,
    sku: 'AMX-10',
    commercialName: 'Amoxival 500',
    category: ANTIBIOTIC,
    dosageForm: null,
    supplier: null,
    baseUnit: 'tablet',
    divisible: false,
    returnRestockForbidden: false,
    vatRate: '9.00',
    purchasePrice: '0.0000',
    salePrice: '0.0125',
    minStock: '10.000',
    maxStock: null,
    reorderPoint: '0.000',
    archived: false,
    sellableQtyBase: '12.000',
    totalQtyBase: '15.000',
    stockStatus: 'low',
    nearestExpiry: '2027-05-31',
    ingredients: [],
    targetSpecies: [],
    withdrawalPeriods: [],
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
    http.get('/api/products/supplier-options', () => HttpResponse.json([])),
  )
}

describe('product pages', () => {
  it('lists products with translated categories, four-decimal prices and their stock', async () => {
    serveMe(PHARMACIST)
    serveReferences()
    const queries: string[] = []
    server.use(
      http.get('/api/products', ({ request }) => {
        queries.push(new URL(request.url).search)
        return HttpResponse.json({ content: [aProduct()], page: { number: 0, size: 20, totalElements: 1, totalPages: 1 } })
      }),
    )
    const router = renderApp('/products')

    const row = (await screen.findByRole('link', { name: 'Amoxival 500' })).closest('tr')!
    expect(within(row).getByText('Antibiotique')).toBeInTheDocument()
    expect(within(row).getByText(/0,0125/)).toBeInTheDocument()
    expect(within(row).getByText('12,000')).toBeInTheDocument()
    expect(within(row).getByText('Stock bas')).toBeInTheDocument()
    expect(within(row).getByText('31 mai 2027')).toBeInTheDocument()
    await userEvent.click(screen.getByLabelText('En stock seulement'))
    await waitFor(() => expect(queries.at(-1)).toContain('inStockOnly=true'))
    await userEvent.click(screen.getByLabelText('En stock seulement'))
    await userEvent.click(screen.getByLabelText('Afficher les produits archivés'))
    expect(screen.getByLabelText('Afficher les produits archivés')).toBeChecked()
    await screen.findByRole('link', { name: 'Amoxival 500' })
    expect(queries.at(-1)).toContain('includeArchived=true')
    expect(router.state.location.search).toBe('?includeArchived=true')
  })

  it('searches after a pause in typing and filters by species, keeping the criteria in the URL', async () => {
    serveMe(PHARMACIST)
    serveReferences()
    server.use(http.get('/api/animal-species', () => HttpResponse.json([{ id: 1, name: 'Bovine', code: 'BOVINE' }])))
    const queries: URLSearchParams[] = []
    server.use(
      http.get('/api/products', ({ request }) => {
        const params = new URL(request.url).searchParams
        queries.push(params)
        return HttpResponse.json({
          content: params.get('q') === 'nothing' ? [] : [aProduct()],
          page: { number: 0, size: 20, totalElements: 1, totalPages: 1 },
        })
      }),
    )
    const router = renderApp('/products')

    await userEvent.type(await screen.findByLabelText('Rechercher'), 'amox')
    await waitFor(() => expect(queries.at(-1)?.get('q')).toBe('amox'))
    expect(queries.filter((q) => q.get('q') === 'a')).toHaveLength(0)
    await userEvent.selectOptions(screen.getByLabelText('Espèce'), '1')
    await waitFor(() => expect(queries.at(-1)?.get('speciesId')).toBe('1'))
    expect(router.state.location.search).toBe('?q=amox&speciesId=1')

    await userEvent.clear(screen.getByLabelText('Rechercher'))
    await userEvent.type(screen.getByLabelText('Rechercher'), 'nothing')
    expect(await screen.findByText('Aucun produit ne correspond à la recherche.')).toBeInTheDocument()
  })

  it('creates a product not subject to VAT by default and shows server field errors (ADR-181)', async () => {
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
    expect(screen.getByRole('radio', { name: 'Non' })).toBeChecked()
    expect(screen.queryByLabelText('TVA (%)')).not.toBeInTheDocument()
    expect(screen.getByText(/^Prix de vente d'une unité de base/)).toBeInTheDocument()
    expect(screen.queryByText(/HT d'une unité de base/)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByText('Ce produit se compte en unités entières.')).toBeInTheDocument()
    expect(bodies[0]).toMatchObject({ sku: 'AMX-10', categoryId: 1, divisible: false, vatRate: '0', minStock: '2.5' })

    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'Amoxival 500' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/products/8')
  })

  it('asks the VAT rate only for a product subject to VAT, prefilled from the settings (ADR-181)', async () => {
    serveMe(PHARMACIST)
    serveReferences()
    const bodies: Record<string, unknown>[] = []
    server.use(
      http.post('/api/products', async ({ request }) => {
        bodies.push((await request.json()) as Record<string, unknown>)
        return HttpResponse.json(aProduct({ id: 8 }), { status: 201 })
      }),
      http.get('/api/products/8', () => HttpResponse.json(aProduct({ id: 8 }))),
    )
    renderApp('/products/new')

    await userEvent.type(await screen.findByLabelText('Nom commercial'), 'Amoxival 500')
    await userEvent.type(screen.getByLabelText('Unité de base'), 'tablet')
    await userEvent.click(screen.getByRole('radio', { name: 'Oui' }))
    expect(screen.getByLabelText('TVA (%)')).toHaveValue('9.00')
    expect(screen.getByText(/^Prix de vente HT d'une unité de base/)).toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText('TVA (%)'))
    await userEvent.type(screen.getByLabelText('TVA (%)'), '0')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(bodies).toHaveLength(0)

    await userEvent.clear(screen.getByLabelText('TVA (%)'))
    await userEvent.type(screen.getByLabelText('TVA (%)'), '19')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    await screen.findByRole('heading', { level: 1, name: 'Amoxival 500' })
    expect(bodies[0]).toMatchObject({ vatRate: '19' })
  })

  it('opens a taxed product on Oui and sends 0 when switched to Non (ADR-181)', async () => {
    serveMe(PHARMACIST)
    serveReferences()
    let body: Record<string, unknown> = {}
    server.use(
      http.get('/api/products/5', () => HttpResponse.json(aProduct())),
      http.put('/api/products/5', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return HttpResponse.json(aProduct({ version: 1, vatRate: '0.00' }))
      }),
    )
    renderApp('/products/5')

    expect(await screen.findByLabelText('TVA (%)')).toHaveValue('9.00')
    expect(screen.getByRole('radio', { name: 'Oui' })).toBeChecked()
    await userEvent.click(screen.getByRole('radio', { name: 'Non' }))
    expect(screen.queryByLabelText('TVA (%)')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await screen.findByText('Modifications enregistrées.')
    expect(body).toMatchObject({ vatRate: '0' })
  })

  it('names a wholesaler or a laboratory as the supplier, the kind beside each name (ADR-180)', async () => {
    serveMe(PHARMACIST)
    serveReferences()
    const bodies: Record<string, unknown>[] = []
    const atlas = { id: 4, name: 'Grossiste Atlas', kind: 'wholesaler', active: true }
    server.use(
      http.get('/api/products/supplier-options', () =>
        HttpResponse.json([atlas, { id: 6, name: 'Labo Vétonord', kind: 'laboratory', active: true }]),
      ),
      http.post('/api/products', async ({ request }) => {
        bodies.push((await request.json()) as Record<string, unknown>)
        return HttpResponse.json(aProduct({ id: 8, supplier: atlas }), { status: 201 })
      }),
      http.get('/api/products/8', () => HttpResponse.json(aProduct({ id: 8, supplier: atlas }))),
    )
    renderApp('/products/new')

    const supplier = await screen.findByLabelText('Fournisseur (grossiste ou laboratoire)')
    expect(await within(supplier).findByRole('option', { name: 'Grossiste Atlas — Grossiste' })).toBeInTheDocument()
    expect(within(supplier).getByRole('option', { name: 'Labo Vétonord — Laboratoire' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Laboratoire')).not.toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Nom commercial'), 'Amoxival 500')
    await userEvent.type(screen.getByLabelText('Unité de base'), 'tablet')
    await userEvent.selectOptions(supplier, '4')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(bodies[0]).toMatchObject({ supplierId: 4 }))
    expect(bodies[0]).not.toHaveProperty('manufacturerId')
  })

  it('sends an empty SKU for the server to generate, and requires one on update (ADR-173)', async () => {
    serveMe(PHARMACIST)
    serveReferences()
    let body: Record<string, unknown> = {}
    server.use(
      http.post('/api/products', async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>
        return HttpResponse.json(aProduct({ id: 9, sku: 'PRD-000001' }), { status: 201 })
      }),
      http.get('/api/products/9', () => HttpResponse.json(aProduct({ id: 9, sku: 'PRD-000001' }))),
    )
    renderApp('/products/new')

    expect(await screen.findByText('Laissez vide pour générer le code (ex. PRD-000001).')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Nom commercial'), 'Sans code')
    await userEvent.type(screen.getByLabelText('Unité de base'), 'ml')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(await screen.findByDisplayValue('PRD-000001')).toBeInTheDocument()
    expect(body).toMatchObject({ sku: '' })

    await userEvent.clear(screen.getByLabelText('SKU'))
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(await screen.findByText('Ce champ est obligatoire.')).toBeInTheDocument()
  })

  it('explains the fields with a help text announced with each of them', async () => {
    serveMe(PHARMACIST)
    serveReferences()
    renderApp('/products/new')

    expect(await screen.findByLabelText('Unité de base')).toHaveAccessibleDescription(
      'La plus petite quantité vendue et comptée (comprimé, ml, flacon…) ; stocks et prix sont dans cette unité.',
    )
    expect(screen.getByLabelText('Catégorie thérapeutique')).toHaveAccessibleDescription(
      'Sert à chercher les produits, aux rapports et aux inventaires par catégorie.',
    )
    expect(screen.getByLabelText('Seuil de réapprovisionnement')).toHaveAccessibleDescription(
      'Quand le stock descend à ce niveau, une proposition de commande est créée.',
    )
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
        HttpResponse.json(aProduct({ ingredients: [{ ingredient: { id: 3, name: 'Amoxicilline', code: null }, strength: '500 mg' }] })),
      ),
      http.get('/api/active-ingredients', () =>
        HttpResponse.json([
          { id: 3, name: 'Amoxicilline', code: null },
          { id: 4, name: 'Acide clavulanique', code: null },
        ]),
      ),
      http.put('/api/products/5/ingredients', async ({ request }) => {
        saved = await request.json()
        return HttpResponse.json([])
      }),
      http.get('/api/products/5/substitutes', () =>
        HttpResponse.json([
          {
            id: 9,
            sku: 'CLAV-1',
            commercialName: 'Clavamox',
            category: null,
            baseUnit: 'tablet',
            salePrice: '1.5000',
            archived: false,
            sellableQtyBase: '40.000',
            totalQtyBase: '40.000',
            stockStatus: 'in_stock',
            nearestExpiry: '2027-01-31',
          },
        ]),
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
    expect(screen.getByText('40,000 tablet')).toBeInTheDocument()
    expect(screen.getByText('En stock')).toBeInTheDocument()
  })

  it('warns, without refusing, when the max stock is at or below a reorder point above zero (ADR-092)', async () => {
    serveMe(PHARMACIST)
    serveReferences()
    let saved: unknown
    server.use(
      http.get('/api/products/5', () => HttpResponse.json(aProduct({ maxStock: '50.000', reorderPoint: '0.000' }))),
      http.put('/api/products/5', async ({ request }) => {
        saved = await request.json()
        return HttpResponse.json(aProduct({ maxStock: '10.000', reorderPoint: '10.000', version: 1 }))
      }),
    )
    renderApp('/products/5')

    const help = 'Quantité à ne pas dépasser ; les propositions de commande complètent jusqu\'à ce niveau.'
    const max = await screen.findByLabelText('Stock maximum')
    expect(max).toHaveAccessibleDescription(help)
    await userEvent.clear(screen.getByLabelText('Seuil de réapprovisionnement'))
    await userEvent.type(screen.getByLabelText('Seuil de réapprovisionnement'), '10')
    expect(max).toHaveAccessibleDescription(help)
    await userEvent.clear(max)
    await userEvent.type(max, '10')
    expect(max).toHaveAccessibleDescription(/stock maximum ≤ seuil de réapprovisionnement/)
    await userEvent.clear(max)
    expect(max).toHaveAccessibleDescription(help)
    await userEvent.type(max, '10')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await screen.findByText('Modifications enregistrées.')
    expect(saved).toMatchObject({ maxStock: '10', reorderPoint: '10' })
  })

  it('reports a duplicated ingredient', async () => {
    serveMe(PHARMACIST)
    serveReferences()
    server.use(
      http.get('/api/products/5', () => HttpResponse.json(aProduct())),
      http.get('/api/active-ingredients', () => HttpResponse.json([{ id: 3, name: 'Amoxicilline', code: null }])),
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

  it('saves target species and withdrawal periods with empty days as not specified', async () => {
    serveMe(PHARMACIST)
    serveReferences()
    const bodies: unknown[] = []
    server.use(
      http.get('/api/products/5', () => HttpResponse.json(aProduct())),
      http.get('/api/animal-species', () =>
        HttpResponse.json([
          { id: 1, name: 'Bovine', code: 'BOVINE' },
          { id: 2, name: 'Ovine', code: 'OVINE' },
        ]),
      ),
      http.put('/api/products/5/target-species', async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json([])
      }),
      http.put('/api/products/5/withdrawal-periods', async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json([])
      }),
    )
    renderApp('/products/5')

    await userEvent.click(await screen.findByRole('tab', { name: "Espèces et délais d'attente" }))
    await userEvent.click(await screen.findByLabelText('Bovin'))
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer les espèces cibles' }))
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter une espèce' }))
    await userEvent.selectOptions(screen.getByLabelText('Espèce 1'), '2')
    await userEvent.type(screen.getByLabelText('Viande 1'), '28')
    await userEvent.type(screen.getByLabelText('Lait 1'), '0')
    await userEvent.click(screen.getByRole('button', { name: "Enregistrer les délais d'attente" }))

    await screen.findAllByText('Modifications enregistrées.')
    expect(bodies).toEqual([{ speciesIds: [1] }, [{ speciesId: 2, meatDays: 28, milkDays: 0, eggsDays: null }]])
  })

  it('shows not-specified days to a reader', async () => {
    serveMe(aUser({ permissions: ['product.read'] }))
    serveReferences()
    server.use(
      http.get('/api/products/5', () =>
        HttpResponse.json(aProduct({ withdrawalPeriods: [{ species: { id: 1, name: 'Bovine', code: 'BOVINE' }, meatDays: 28, milkDays: null, eggsDays: 0 }] })),
      ),
    )
    renderApp('/products/5')

    await userEvent.click(await screen.findByRole('tab', { name: "Espèces et délais d'attente" }))
    const row = screen.getByRole('cell', { name: 'Bovin' }).closest('tr')!
    expect(row).toHaveTextContent('28')
    expect(row).toHaveTextContent('non précisé')
    expect(row).toHaveTextContent('0')
  })
})

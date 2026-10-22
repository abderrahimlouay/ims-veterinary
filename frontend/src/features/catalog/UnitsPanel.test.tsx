import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const PRICED = aUser({ permissions: ['product.read', 'product.update', 'price.edit'] })
const NO_PRICES = aUser({ permissions: ['product.read', 'product.update'] })

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
const BLISTER = { id: 9, name: 'Blister', factorToBase: '10.0000', salePrice: '0.15', effectiveSalePrice: '0.1500', sellable: false, version: 2 }

function serveProduct(units: unknown[]) {
  server.use(
    http.get('/api/products/5', () => HttpResponse.json(PRODUCT)),
    http.get('/api/categories', () => HttpResponse.json([])),
    http.get('/api/dosage-forms', () => HttpResponse.json([])),
    http.get('/api/manufacturers', () => HttpResponse.json([])),
    http.get('/api/products/5/units', () => HttpResponse.json(units)),
  )
}

async function openUnits() {
  await userEvent.click(await screen.findByRole('tab', { name: 'Unités' }))
}

describe('units tab (FR-PROD-006/007)', () => {
  it('lists units with their content and stored or derived price', async () => {
    serveMe(PRICED)
    serveProduct([BLISTER, BOX])
    renderApp('/products/5')
    await openUnits()

    const box = (await screen.findByRole('cell', { name: 'Box' })).closest('tr')!
    expect(within(box).getByText('30 tablet')).toBeInTheDocument()
    expect(within(box).getByText(/0,375.*\(calculé\)/)).toBeInTheDocument()
    expect(within(box).getByText('Oui')).toBeInTheDocument()
    const blister = screen.getByRole('cell', { name: 'Blister' }).closest('tr')!
    expect(within(blister).getByText(/0,15/)).not.toHaveTextContent('calculé')
    expect(within(blister).getByText('Non')).toBeInTheDocument()
  })

  it('shows an empty state naming the base unit', async () => {
    serveMe(PRICED)
    serveProduct([])
    renderApp('/products/5')
    await openUnits()

    expect(await screen.findByText(/Aucune unité de conditionnement.*tablet/)).toBeInTheDocument()
  })

  it('adds a unit with an empty price as derived and places server field errors', async () => {
    serveMe(PRICED)
    serveProduct([])
    const bodies: unknown[] = []
    server.use(
      http.post('/api/products/5/units', async ({ request }) => {
        bodies.push(await request.json())
        return bodies.length === 1
          ? problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'factorToBase', code: 'WHOLE_NUMBER', message: 'x' }] })
          : HttpResponse.json(BOX, { status: 201 })
      }),
    )
    renderApp('/products/5')
    await openUnits()

    await userEvent.type(await screen.findByLabelText("Nom de l'unité"), 'Box')
    await userEvent.type(screen.getByLabelText('Unités de base par unité (tablet)'), '2.5')
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter' }))
    expect(await screen.findByText('Ce produit se compte en unités entières.')).toBeInTheDocument()

    await userEvent.clear(screen.getByLabelText('Unités de base par unité (tablet)'))
    await userEvent.type(screen.getByLabelText('Unités de base par unité (tablet)'), '30')
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter' }))
    await screen.findByText('Modifications enregistrées.')
    expect(bodies[1]).toEqual({ name: 'Box', factorToBase: '30', salePrice: null, sellable: true })
  })

  it('puts a duplicate name on its field', async () => {
    serveMe(PRICED)
    serveProduct([BOX])
    server.use(http.post('/api/products/5/units', () => problem(409, 'DUPLICATE_RESOURCE', { context: { field: 'name' } })))
    renderApp('/products/5')
    await openUnits()

    await userEvent.type(await screen.findByLabelText("Nom de l'unité"), 'box')
    await userEvent.type(screen.getByLabelText('Unités de base par unité (tablet)'), '20')
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter' }))

    expect(await screen.findByText('Cette valeur est déjà utilisée.')).toBeInTheDocument()
  })

  it('edits a unit with its version and explains a locked factor', async () => {
    serveMe(PRICED)
    serveProduct([BLISTER])
    let body: unknown
    server.use(
      http.put('/api/products/5/units/9', async ({ request }) => {
        body = await request.json()
        return problem(422, 'UNIT_FACTOR_LOCKED', { context: { field: 'factorToBase' } })
      }),
    )
    renderApp('/products/5')
    await openUnits()

    await userEvent.click(await screen.findByRole('button', { name: 'Modifier Blister' }))
    expect(screen.getByLabelText("Prix de vente de l'unité")).toHaveValue('0.15')
    await userEvent.clear(screen.getByLabelText('Unités de base par unité (tablet)'))
    await userEvent.type(screen.getByLabelText('Unités de base par unité (tablet)'), '12')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('déjà utilisée dans des documents')
    expect(body).toEqual({ name: 'Blister', factorToBase: '12', salePrice: '0.15', sellable: false, version: 2 })
  })

  it('without price.edit keeps the unit price read-only and sends it unchanged', async () => {
    serveMe(NO_PRICES)
    serveProduct([BLISTER])
    let body: unknown
    server.use(
      http.put('/api/products/5/units/9', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ ...BLISTER, name: 'Strip', version: 3 })
      }),
    )
    renderApp('/products/5')
    await openUnits()

    await userEvent.click(await screen.findByRole('button', { name: 'Modifier Blister' }))
    expect(screen.getByLabelText("Prix de vente de l'unité")).toBeDisabled()
    expect(screen.getByText(/modifier les prix/)).toBeInTheDocument()
    await userEvent.clear(screen.getByLabelText("Nom de l'unité"))
    await userEvent.type(screen.getByLabelText("Nom de l'unité"), 'Strip')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(body).toEqual({ name: 'Strip', factorToBase: '10.0000', salePrice: '0.15', sellable: false, version: 2 }))
  })

  it('is read-only for a reader', async () => {
    serveMe(aUser({ permissions: ['product.read'] }))
    serveProduct([BOX])
    renderApp('/products/5')
    await openUnits()

    expect(await screen.findByRole('cell', { name: 'Box' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Modifier Box' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText("Nom de l'unité")).not.toBeInTheDocument()
  })

  it('shows a load failure with its reference', async () => {
    serveMe(PRICED)
    serveProduct([])
    server.use(http.get('/api/products/5/units', () => problem(500, 'INTERNAL_ERROR')))
    renderApp('/products/5')
    await openUnits()

    expect(await screen.findByRole('alert')).toHaveTextContent('trace-1')
  })
})

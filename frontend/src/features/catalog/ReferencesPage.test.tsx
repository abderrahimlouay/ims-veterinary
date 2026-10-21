import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import { i18n } from '../../lib/i18n'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const PHARMACIST = aUser({ permissions: ['product.read', 'reference.manage'] })
const SPECIES = [
  { id: 1, name: 'Bovine', code: 'BOVINE', country: null },
  { id: 9, name: 'Camelin', code: null, country: null },
]

describe('ReferencesPage (ADR-054)', () => {
  afterEach(async () => {
    await i18n.changeLanguage('fr')
  })

  it('translates seeded entries, shows added ones as typed and protects the seeded ones', async () => {
    serveMe(PHARMACIST)
    server.use(http.get('/api/animal-species', () => HttpResponse.json(SPECIES)))
    renderApp('/catalog/references')

    const bovine = (await screen.findByText('Bovin')).closest('li')!
    expect(within(bovine).getByText('Valeur de base')).toBeInTheDocument()
    expect(within(bovine).queryByRole('button')).not.toBeInTheDocument()
    const added = screen.getByText('Camelin').closest('li')!
    expect(within(added).getByRole('button', { name: 'Renommer' })).toBeInTheDocument()

    await i18n.changeLanguage('ar')
    expect(await screen.findByText('أبقار')).toBeInTheDocument()
    expect(screen.getByText('Camelin')).toBeInTheDocument()
  })

  it('adds an entry and reports a duplicate on the field', async () => {
    serveMe(PHARMACIST)
    let attempts = 0
    server.use(
      http.get('/api/manufacturers', () => HttpResponse.json([])),
      http.post('/api/manufacturers', async ({ request }) => {
        attempts += 1
        const body = (await request.json()) as { name: string; country: string }
        return attempts === 1
          ? problem(409, 'DUPLICATE_RESOURCE', { context: { field: 'name' } })
          : HttpResponse.json({ id: 3, code: null, ...body }, { status: 201 })
      }),
    )
    renderApp('/catalog/references')

    await userEvent.click(await screen.findByRole('tab', { name: 'Laboratoires' }))
    expect(await screen.findByText('Aucune entrée pour le moment.')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Nouvelle entrée'), 'Vétoquinol')
    await userEvent.type(screen.getByLabelText('Pays'), 'France')
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter' }))
    expect(await screen.findByText('Cette valeur est déjà utilisée.')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Ajouter' }))
    expect(attempts).toBe(2)
  })

  it('explains that a used entry cannot be deleted', async () => {
    serveMe(PHARMACIST)
    server.use(
      http.get('/api/animal-species', () => HttpResponse.json(SPECIES)),
      http.delete('/api/animal-species/9', () => problem(409, 'RESOURCE_IN_USE')),
    )
    renderApp('/catalog/references')

    await userEvent.click(await screen.findByRole('button', { name: 'Supprimer Camelin' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Cette entrée est utilisée et ne peut pas être supprimée.')
  })

  it('is read-only without reference.manage', async () => {
    serveMe(aUser({ permissions: ['product.read'] }))
    server.use(http.get('/api/animal-species', () => HttpResponse.json(SPECIES)))
    renderApp('/catalog/references')

    expect(await screen.findByText('Camelin')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Renommer' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Nouvelle entrée')).not.toBeInTheDocument()
  })

  it('shows the forbidden page without product.read', async () => {
    serveMe(aUser({ permissions: [] }))
    renderApp('/catalog/references')

    expect(await screen.findByRole('heading', { level: 1, name: 'Accès refusé' })).toBeInTheDocument()
  })
})

import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, anAdmin, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const ROLES = [
  { id: 1, name: 'Administrator', description: null, permissions: [] },
  { id: 3, name: 'Seller', description: null, permissions: [] },
]

function aDetail(overrides: Record<string, unknown> = {}) {
  return {
    id: 9,
    username: 'karim',
    fullName: 'Karim Saidi',
    email: null,
    role: { id: 3, name: 'Seller' },
    active: true,
    mustChangePassword: false,
    lastLoginAt: null,
    createdAt: '2026-10-20T08:00:00Z',
    version: 0,
    ...overrides,
  }
}

function serveAdminData() {
  server.use(http.get('/api/roles', () => HttpResponse.json(ROLES)))
}

describe('users administration', () => {
  it('lists users with filters and offers creation to user.manage holders', async () => {
    serveMe(anAdmin())
    serveAdminData()
    const queries: string[] = []
    server.use(
      http.get('/api/users', ({ request }) => {
        queries.push(new URL(request.url).search)
        return HttpResponse.json({
          content: [aDetail()],
          page: { number: 0, size: 20, totalElements: 1, totalPages: 1 },
        })
      }),
    )
    renderApp('/admin/users')

    expect(await screen.findByRole('link', { name: 'karim' })).toHaveAttribute('href', '/admin/users/9')
    expect(screen.getByRole('link', { name: 'Nouvel utilisateur' })).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('Statut'), 'false')

    await screen.findByRole('link', { name: 'karim' })
    expect(queries.at(-1)).toContain('active=false')
  })

  it('hides the admin entries and pages from a user without the permissions', async () => {
    serveMe(aUser())
    renderApp('/admin/users')

    expect(await screen.findByRole('heading', { level: 1, name: 'Accès refusé' })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Navigation principale' })
    expect(within(nav).queryByRole('link', { name: 'Utilisateurs' })).not.toBeInTheDocument()
  })

  it('creates a user and puts a duplicate username on its field', async () => {
    serveMe(anAdmin())
    serveAdminData()
    let attempts = 0
    server.use(
      http.post('/api/users', () => {
        attempts += 1
        return attempts === 1
          ? problem(409, 'DUPLICATE_RESOURCE', { context: { field: 'username' } })
          : HttpResponse.json(aDetail({ id: 12, username: 'karim2' }), { status: 201 })
      }),
      http.get('/api/users/12', () => HttpResponse.json(aDetail({ id: 12, username: 'karim2' }))),
    )
    const router = renderApp('/admin/users/new')

    await userEvent.type(await screen.findByLabelText("Nom d'utilisateur"), 'karim')
    await userEvent.type(screen.getByLabelText('Nom complet'), 'Karim Saidi')
    await userEvent.selectOptions(screen.getByLabelText('Rôle'), '3')
    const initial = screen.getByLabelText('Mot de passe initial')
    expect(initial).toHaveAttribute('type', 'password')
    expect(initial).toHaveAttribute('autocomplete', 'new-password')
    await userEvent.type(initial, 'Amber-meadow-quartz-11')
    await userEvent.click(screen.getByRole('button', { name: 'Afficher le mot de passe' }))
    expect(initial).toHaveAttribute('type', 'text')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(await screen.findByText('Cette valeur est déjà utilisée.')).toBeInTheDocument()
    expect(initial).toHaveAttribute('type', 'password')

    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'karim2' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/admin/users/12')
  })

  it('reports a stale version and a lock-out refusal', async () => {
    serveMe(anAdmin())
    serveAdminData()
    server.use(
      http.get('/api/users/9', () => HttpResponse.json(aDetail())),
      http.put('/api/users/9', () => problem(409, 'CONCURRENT_MODIFICATION')),
      http.post('/api/users/9/deactivate', () => problem(409, 'INVALID_STATE_TRANSITION', { detail: 'Lock-out.' })),
    )
    renderApp('/admin/users/9')

    await userEvent.click(await screen.findByRole('button', { name: 'Enregistrer' }))
    expect(await screen.findByText(/modifiée par quelqu'un d'autre/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Désactiver' }))
    expect(await screen.findByText('Lock-out.')).toBeInTheDocument()
  })

  it('shows the temporary password once after a reset and never offers self-deactivation', async () => {
    serveMe(anAdmin())
    serveAdminData()
    server.use(
      http.get('/api/users/1', () => HttpResponse.json(aDetail({ id: 1, username: 'admin' }))),
      // Test fixture, not a secret: the format of ADR-045.
      http.post('/api/users/1/reset-password', () => HttpResponse.json({ temporaryPassword: 'k7mq-3xht-9pwd-r2va' })), // gitleaks:allow
    )
    renderApp('/admin/users/1')

    await userEvent.click(await screen.findByRole('button', { name: 'Réinitialiser le mot de passe' }))

    const temporary = await screen.findByTestId('temporary-password')
    expect(temporary).toHaveValue('k7mq-3xht-9pwd-r2va')
    expect(temporary).toHaveAttribute('type', 'password')
    expect(temporary).toHaveAttribute('readonly')
    await userEvent.click(screen.getByRole('button', { name: 'Afficher le mot de passe' }))
    expect(temporary).toHaveAttribute('type', 'text')
    expect(screen.queryByRole('button', { name: 'Désactiver' })).not.toBeInTheDocument()
  })
})

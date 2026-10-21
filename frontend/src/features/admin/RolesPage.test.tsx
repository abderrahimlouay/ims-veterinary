import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { anAdmin, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

function serveRoles() {
  server.use(
    http.get('/api/roles', () =>
      HttpResponse.json([
        { id: 1, name: 'Administrator', description: null, permissions: ['audit.read', 'role.manage'] },
        { id: 3, name: 'Seller', description: null, permissions: ['sale.read'] },
      ]),
    ),
    http.get('/api/permissions', () =>
      HttpResponse.json([
        { code: 'audit.read', description: 'read audit log' },
        { code: 'role.manage', description: 'edit roles' },
        { code: 'sale.read', description: 'view sales' },
      ]),
    ),
  )
}

describe('RolesPage', () => {
  it('reviews the changes before saving them role by role', async () => {
    serveMe(anAdmin())
    serveRoles()
    const saved: unknown[] = []
    server.use(
      http.put('/api/roles/3/permissions', async ({ request }) => {
        saved.push(await request.json())
        return HttpResponse.json({ id: 3, name: 'Seller', description: null, permissions: [] })
      }),
    )
    renderApp('/admin/roles')

    await userEvent.click(await screen.findByLabelText('Seller audit.read'))
    await userEvent.click(screen.getByLabelText('Seller sale.read'))
    await userEvent.click(screen.getByRole('button', { name: 'Vérifier les modifications (1)' }))

    expect(screen.getByText('+ audit.read')).toBeInTheDocument()
    expect(screen.getByText('− sale.read')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer ces modifications' }))

    expect(await screen.findByText('Modifications enregistrées.')).toBeInTheDocument()
    expect(saved).toEqual([{ permissionCodes: ['audit.read'] }])
  })

  it('shows a lock-out refusal', async () => {
    serveMe(anAdmin())
    serveRoles()
    server.use(http.put('/api/roles/1/permissions', () => problem(409, 'INVALID_STATE_TRANSITION', { detail: 'Lock-out.' })))
    renderApp('/admin/roles')

    await userEvent.click(await screen.findByLabelText('Administrator role.manage'))
    await userEvent.click(screen.getByRole('button', { name: 'Vérifier les modifications (1)' }))
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer ces modifications' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Lock-out.')
  })
})

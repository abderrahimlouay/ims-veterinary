import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

async function change(current: string, next: string, confirm = next) {
  await userEvent.type(await screen.findByLabelText('Mot de passe actuel'), current)
  await userEvent.type(screen.getByLabelText('Nouveau mot de passe'), next)
  await userEvent.type(screen.getByLabelText('Confirmer le nouveau mot de passe'), confirm)
  await userEvent.click(screen.getByRole('button', { name: 'Changer le mot de passe' }))
}

describe('AccountPage', () => {
  it('shows the profile', async () => {
    serveMe(aUser({ user: { id: 1, username: 'amina', fullName: 'Amina Benali', email: 'a@x.dz', role: 'Seller' } }))
    renderApp('/account')

    const main = await screen.findByRole('main')
    expect(await within(main).findByText('Amina Benali')).toBeInTheDocument()
    expect(within(main).getByText('a@x.dz')).toBeInTheDocument()
  })

  it('changes the password and confirms it', async () => {
    serveMe(aUser())
    let body: unknown
    server.use(
      http.post('/api/auth/change-password', async ({ request }) => {
        body = await request.json()
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderApp('/account')

    await change('Tall-cedar-river-2026', 'Quiet-harbour-lantern-7')

    expect(await screen.findByRole('status')).toHaveTextContent('Votre mot de passe a été changé.')
    expect(body).toEqual({ currentPassword: 'Tall-cedar-river-2026', newPassword: 'Quiet-harbour-lantern-7' })
  })

  it('gives each password field its own eye', async () => {
    serveMe(aUser())
    renderApp('/account')
    const current = await screen.findByLabelText('Mot de passe actuel')
    const eyes = screen.getAllByRole('button', { name: 'Afficher le mot de passe' })
    expect(eyes).toHaveLength(3)
    expect(current).toHaveAttribute('autocomplete', 'current-password')
    expect(screen.getByLabelText('Nouveau mot de passe')).toHaveAttribute('autocomplete', 'new-password')
    expect(screen.getByLabelText('Confirmer le nouveau mot de passe')).toHaveAttribute('autocomplete', 'new-password')

    await userEvent.click(eyes[1])

    expect(screen.getByLabelText('Nouveau mot de passe')).toHaveAttribute('type', 'text')
    expect(current).toHaveAttribute('type', 'password')
    expect(screen.getByLabelText('Confirmer le nouveau mot de passe')).toHaveAttribute('type', 'password')
  })

  it('checks length and confirmation before calling the server', async () => {
    serveMe(aUser())
    renderApp('/account')

    await change('Tall-cedar-river-2026', 'short', 'other')

    expect(await screen.findByText('Utilisez au moins 12 caractères.')).toBeInTheDocument()
    expect(screen.getByText('Les deux mots de passe ne sont pas identiques.')).toBeInTheDocument()
  })

  it('puts server field errors on their fields', async () => {
    serveMe(aUser())
    server.use(
      http.post('/api/auth/change-password', () =>
        problem(400, 'VALIDATION_FAILED', {
          errors: [
            { field: 'currentPassword', code: 'INVALID', message: 'x' },
            { field: 'newPassword', code: 'TOO_COMMON', message: 'x' },
          ],
        }),
      ),
    )
    renderApp('/account')

    await change('wrong-current-pw', 'Q1W2E3R4T5Y6')

    expect(await screen.findByText("Le mot de passe actuel n'est pas correct.")).toBeInTheDocument()
    expect(screen.getByText('Ce mot de passe est trop courant.')).toBeInTheDocument()
  })

  it('returns to the dashboard after the forced change', async () => {
    let mustChange = true
    server.use(
      http.get('/api/auth/me', () => HttpResponse.json(aUser({ mustChangePassword: mustChange }))),
      http.post('/api/auth/change-password', () => {
        mustChange = false
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const router = renderApp('/account')

    await change('k7mq-3xht-9pwd-r2va', 'Quiet-harbour-lantern-7')

    expect(await screen.findByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/')
  })
})

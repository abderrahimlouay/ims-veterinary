import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

async function logIn(username: string, password: string) {
  await userEvent.type(await screen.findByLabelText("Nom d'utilisateur"), username)
  await userEvent.type(screen.getByLabelText('Mot de passe'), password)
  await userEvent.click(screen.getByRole('button', { name: 'Se connecter' }))
}

describe('LoginPage', () => {
  it('logs in and returns to the requested page', async () => {
    serveMe(null)
    let body: unknown
    server.use(
      http.post('/api/auth/login', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(aUser())
      }),
    )
    const router = renderApp('/login?redirect=%2Faccount')

    await logIn('amina', 'Tall-cedar-river-2026')

    expect(await screen.findByRole('heading', { level: 1, name: 'Mon compte' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/account')
    expect(body).toEqual({ username: 'amina', password: 'Tall-cedar-river-2026' })
  })

  it('ignores a return target outside the application', async () => {
    serveMe(null)
    server.use(http.post('/api/auth/login', () => HttpResponse.json(aUser())))
    const router = renderApp('/login?redirect=%2F%2Fevil.example')

    await logIn('amina', 'Tall-cedar-river-2026')

    expect(await screen.findByRole('heading', { level: 1, name: 'Tableau de bord' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/')
  })

  it('sends a user with a temporary password to the account page', async () => {
    serveMe(null)
    server.use(http.post('/api/auth/login', () => HttpResponse.json(aUser({ mustChangePassword: true }))))
    const router = renderApp('/login')

    await logIn('amina', 'k7mq-3xht-9pwd-r2va')

    expect(await screen.findByText(/Votre mot de passe est temporaire/)).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/account')
  })

  it.each([
    [problem(401, 'AUTH_INVALID_CREDENTIALS'), "Nom d'utilisateur ou mot de passe incorrect."],
    [problem(403, 'AUTH_ACCOUNT_DISABLED'), 'Ce compte est désactivé. Contactez un administrateur.'],
    [problem(429, 'RATE_LIMITED', { context: { retryAfterSeconds: 900 } }), 'Trop de tentatives. Réessayez dans 15 min.'],
  ])('explains a refused login', async (response, message) => {
    serveMe(null)
    server.use(http.post('/api/auth/login', () => response))
    renderApp('/login')

    await logIn('amina', 'wrong-password-1')

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
  })

  it('hides the password by default and again after a refused login', async () => {
    serveMe(null)
    server.use(http.post('/api/auth/login', () => problem(401, 'AUTH_INVALID_CREDENTIALS')))
    renderApp('/login')
    const password = await screen.findByLabelText('Mot de passe')
    expect(password).toHaveAttribute('type', 'password')
    expect(password).toHaveAttribute('autocomplete', 'current-password')

    await userEvent.type(screen.getByLabelText("Nom d'utilisateur"), 'amina')
    await userEvent.type(password, 'wrong-password-1')
    await userEvent.click(screen.getByRole('button', { name: 'Afficher le mot de passe' }))
    expect(password).toHaveAttribute('type', 'text')
    await userEvent.click(password)
    await userEvent.keyboard('{Enter}')

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(password).toHaveAttribute('type', 'password')
    expect(screen.getByRole('button', { name: 'Afficher le mot de passe' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('validates required fields before calling the server', async () => {
    serveMe(null)
    renderApp('/login')

    await userEvent.click(await screen.findByRole('button', { name: 'Se connecter' }))

    expect(await screen.findAllByText('Ce champ est obligatoire.')).toHaveLength(2)
  })
})

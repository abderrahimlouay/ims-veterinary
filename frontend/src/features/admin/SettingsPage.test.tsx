import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { anAdmin, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const SETTINGS = [
  { key: 'currency', value: 'DZD', description: null, editable: false, updatedAt: '2026-10-20T08:00:00Z' },
  { key: 'default_vat_rate', value: '9.00', description: null, editable: true, updatedAt: '2026-10-20T08:00:00Z' },
  { key: 'expiry_alert_days', value: '60,30,15', description: null, editable: true, updatedAt: '2026-10-20T08:00:00Z' },
]

describe('SettingsPage', () => {
  it('shows read-only settings as text and saves an edited one', async () => {
    serveMe(anAdmin())
    let body: unknown
    server.use(
      http.get('/api/settings', () => HttpResponse.json(SETTINGS)),
      http.put('/api/settings/default_vat_rate', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ ...SETTINGS[1], value: '19.00' })
      }),
    )
    renderApp('/admin/settings')

    expect(await screen.findByText('DZD')).toBeInTheDocument()
    expect(screen.queryByLabelText('Devise')).not.toBeInTheDocument()
    const vat = screen.getByLabelText('Taux de TVA par défaut (%)')
    await userEvent.clear(vat)
    await userEvent.type(vat, '19')
    await userEvent.click(screen.getAllByRole('button', { name: 'Enregistrer' })[0])

    expect(body).toEqual({ value: '19' })
  })

  it('checks the expiry format locally and shows server field errors', async () => {
    serveMe(anAdmin())
    let calls = 0
    server.use(
      http.get('/api/settings', () => HttpResponse.json(SETTINGS)),
      http.put('/api/settings/expiry_alert_days', () => {
        calls += 1
        return problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'value', code: 'INVALID_FORMAT', message: 'x' }] })
      }),
    )
    renderApp('/admin/settings')

    const days = await screen.findByLabelText("Seuils d'alerte de péremption (jours)")
    await userEvent.clear(days)
    await userEvent.type(days, '30,60,15')
    await userEvent.click(screen.getAllByRole('button', { name: 'Enregistrer' })[1])
    expect(await screen.findByText('Format invalide.')).toBeInTheDocument()
    expect(calls).toBe(0)

    await userEvent.clear(days)
    await userEvent.type(days, '90,30,7')
    await userEvent.click(screen.getAllByRole('button', { name: 'Enregistrer' })[1])
    expect(await screen.findByText('Format invalide.')).toBeInTheDocument()
    expect(calls).toBe(1)
  })
})

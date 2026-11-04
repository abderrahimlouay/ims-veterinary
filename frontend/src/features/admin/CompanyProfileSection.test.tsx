import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { anAdmin, problem, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'
import { profileForm, type CompanyField } from './companyApi'

const PROFILE = {
  id: 3,
  legalName: 'Pharmacie Vétérinaire Démo',
  legalForm: 'SARL',
  address: null,
  wilaya: 'Alger',
  phone: null,
  email: null,
  nif: '000016001234567',
  nis: null,
  rc: null,
  ai: null,
  approvalNumber: null,
  bankAccount: null,
  footerText: null,
  hasLogo: true,
  effectiveFrom: '2026-11-04T09:00:00Z',
  createdBy: 1,
}

function serve(current: unknown) {
  server.use(
    http.get('/api/settings', () => HttpResponse.json([])),
    http.get('/api/company-profile', () => (current === null ? new HttpResponse(null, { status: 204 }) : HttpResponse.json(current))),
    http.get('/api/company-profile/logo', () => new HttpResponse(new Uint8Array([0x89, 0x50, 0x4e, 0x47]), { headers: { 'Content-Type': 'image/png' } })),
  )
}

describe('company profile (ADR-140)', () => {
  it('records the first version as multipart, the legal name required first', async () => {
    serveMe(anAdmin())
    serve(null)
    let sent: string | undefined
    server.use(
      http.post('/api/company-profile', async ({ request }) => {
        sent = await request.text()
        return HttpResponse.json({ ...PROFILE, id: 4 }, { status: 201 })
      }),
    )
    renderApp('/admin/settings')
    expect(await screen.findByText("Aucune identité enregistrée : les documents l'indiqueront.")).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer une nouvelle version' }))
    expect(screen.getByText('Ce champ est obligatoire.')).toBeInTheDocument()
    expect(sent).toBeUndefined()

    await userEvent.type(screen.getByLabelText('Raison sociale'), 'Pharmacie Vétérinaire Démo')
    await userEvent.type(screen.getByLabelText('NIF'), '000016001234567')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer une nouvelle version' }))

    await waitFor(() => expect(sent).toBeDefined())
    expect(sent).toMatch(/name="legalName"\r\n\r\nPharmacie Vétérinaire Démo\r\n/)
    expect(sent).toMatch(/name="nif"\r\n\r\n000016001234567\r\n/)
    expect(sent).not.toContain('name="nis"')
    expect(sent).not.toContain('name="logo"')
    expect(sent).toMatch(/name="keepLogo"\r\n\r\nfalse\r\n/)
    expect(await screen.findByText('Nouvelle version enregistrée.')).toBeInTheDocument()
  })

  it('keeps the current logo by default and puts a server error on its field', async () => {
    serveMe(anAdmin())
    serve(PROFILE)
    let sent: FormData | undefined
    server.use(
      http.post('/api/company-profile', async ({ request }) => {
        sent = await request.formData()
        return problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'email', code: 'EMAIL', message: '' }] })
      }),
    )
    renderApp('/admin/settings')
    expect(await screen.findByLabelText('Raison sociale')).toHaveValue('Pharmacie Vétérinaire Démo')
    expect(screen.getByLabelText('Forme juridique')).toHaveValue('SARL')
    expect(screen.getByRole('img', { name: 'Logo actuel' })).toBeInTheDocument()
    expect(screen.getByLabelText('Garder le logo actuel')).toBeChecked()
    await userEvent.type(screen.getByLabelText('E-mail'), 'pas-un-mail')
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer une nouvelle version' }))

    await waitFor(() => expect(sent).toBeDefined())
    expect(sent?.get('keepLogo')).toBe('true')
    expect(sent?.has('logo')).toBe(false)
    expect(sent?.get('legalForm')).toBe('SARL')
    expect(await screen.findByLabelText('E-mail')).toHaveAttribute('aria-invalid', 'true')
  })

  it('refuses a logo over 256 KB before sending it', async () => {
    serveMe(anAdmin())
    serve(PROFILE)
    let calls = 0
    server.use(
      http.post('/api/company-profile', () => {
        calls += 1
        return HttpResponse.json(PROFILE, { status: 201 })
      }),
    )
    renderApp('/admin/settings')
    await userEvent.upload(await screen.findByLabelText('Logo (PNG ou JPEG, 256 Ko au plus)'), new File([new Uint8Array(256 * 1024 + 1)], 'big.png', { type: 'image/png' }))
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer une nouvelle version' }))
    expect(screen.getByText('Le logo dépasse 256 Ko.')).toBeInTheDocument()
    expect(calls).toBe(0)
  })

  it('builds the body with the trimmed filled fields and the new logo (jsdom cannot send a File through fetch)', () => {
    const values = { legalName: ' Pharmacie Démo ', legalForm: '', nif: 'NIF-1' } as Record<CompanyField, string>
    const filled = Object.fromEntries(['legalName', 'legalForm', 'address', 'wilaya', 'phone', 'email', 'nif', 'nis', 'rc', 'ai', 'approvalNumber', 'bankAccount', 'footerText'].map((f) => [f, values[f as CompanyField] ?? ''])) as Record<CompanyField, string>
    const form = profileForm(filled, new File([new Uint8Array([0x89, 0x50])], 'logo.png', { type: 'image/png' }), false)
    expect(form.get('legalName')).toBe('Pharmacie Démo')
    expect(form.has('legalForm')).toBe(false)
    expect(form.get('nif')).toBe('NIF-1')
    expect((form.get('logo') as File).name).toBe('logo.png')
    expect(form.get('keepLogo')).toBe('false')
  })
})

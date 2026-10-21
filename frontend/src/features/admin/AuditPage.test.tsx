import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { anAdmin, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const ENTRY = {
  id: 1,
  userId: 1,
  username: 'admin',
  userFullName: 'Amina Benali',
  action: 'SETTING_CHANGE',
  entity: 'app_settings',
  entityId: null,
  oldValues: { key: 'default_vat_rate', value: '9.00' },
  newValues: { key: 'default_vat_rate', value: '19.00' },
  ipAddress: '192.0.2.1',
  createdAt: '2026-10-20T23:30:00Z',
}

describe('AuditPage', () => {
  it('names the user (ADR-046), shows system actions, details and filters by action', async () => {
    serveMe(anAdmin())
    const queries: string[] = []
    server.use(
      http.get('/api/audit-logs', ({ request }) => {
        queries.push(new URL(request.url).search)
        return HttpResponse.json({
          content: [ENTRY, { ...ENTRY, id: 2, userId: null, username: null, userFullName: null, action: 'USER_CREATE' }],
          page: { number: 0, size: 20, totalElements: 2, totalPages: 1 },
        })
      }),
    )
    renderApp('/admin/audit')

    expect(await screen.findByText('admin (Amina Benali)')).toBeInTheDocument()
    expect(screen.getByText('Système')).toBeInTheDocument()
    expect(screen.getAllByText(/^21\/10\/2026/)).toHaveLength(2)

    await userEvent.click(screen.getAllByRole('button', { name: 'Détails' })[0])
    expect(screen.getByRole('complementary', { name: 'Détails' })).toHaveTextContent('"19.00"')

    await userEvent.selectOptions(screen.getByLabelText('Action'), 'USER_CREATE')
    await screen.findByText('admin (Amina Benali)')
    expect(queries.at(-1)).toContain('action=USER_CREATE')
  })
})

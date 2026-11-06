import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { aUser, serveMe } from '../../test/auth'
import { renderApp } from '../../test/render'
import { server } from '../../test/server'

const KEPT = {
  id: 3,
  trigger: 'scheduled',
  startedBy: null,
  status: 'success',
  fileName: 'ims-backup-20261020-010000-1.sql.gz',
  sizeBytes: 2_500_000,
  failureReason: null,
  copyStatus: null,
  copyFailureReason: null,
  startedAt: '2026-10-20T01:00:00Z',
  finishedAt: '2026-10-20T01:00:05Z',
  removedAt: null,
  lastDownloadedAt: null,
  available: true,
}

const FAILED = { ...KEPT, id: 4, trigger: 'manual', startedBy: 1, status: 'failed', fileName: null, sizeBytes: null, failureReason: 'mysqldump stopped with code 2: Access denied', available: false }

describe('BackupsPage', () => {
  it('shows the warnings, the log with downloads, and runs a backup now (ADR-144…146)', async () => {
    serveMe(aUser({ permissions: ['backup.manage'] }))
    let runs = 0
    server.use(
      http.get('/api/backups/status', () =>
        HttpResponse.json({
          configured: true,
          lastRun: FAILED,
          lastSuccessAt: KEPT.finishedAt,
          lastDownloadAt: null,
          downloadReminderDays: 7,
          copyConfigured: false,
          lastCopyAt: null,
          warnings: ['LAST_FAILED', 'DOWNLOAD_OVERDUE'],
        }),
      ),
      http.get('/api/backups', () =>
        HttpResponse.json({ content: [FAILED, KEPT], page: { number: 0, size: 20, totalElements: 2, totalPages: 1 } }),
      ),
      http.post('/api/backups', () => {
        runs += 1
        return HttpResponse.json({ ...KEPT, id: 5, trigger: 'manual', startedBy: 1 }, { status: 201 })
      }),
    )
    renderApp('/admin/backups')

    expect(await screen.findByText('La dernière sauvegarde a échoué.')).toBeInTheDocument()
    expect(screen.getByText('Aucune sauvegarde téléchargée depuis 7 jours : faites une copie hors site.')).toBeInTheDocument()
    expect(await screen.findByText('mysqldump stopped with code 2: Access denied')).toBeInTheDocument()
    expect(screen.getByText('2,4 Mo')).toBeInTheDocument()
    const download = screen.getByRole('link', { name: 'Télécharger' })
    expect(download).toHaveAttribute('href', '/api/backups/3/download')
    expect(screen.getAllByRole('link', { name: 'Télécharger' })).toHaveLength(1)

    await userEvent.click(screen.getByRole('button', { name: 'Lancer une sauvegarde' }))
    expect(await screen.findByText('Sauvegarde terminée : ims-backup-20261020-010000-1.sql.gz')).toBeInTheDocument()
    expect(runs).toBe(1)
  })

  it('with a Google Drive copy folder, shows each copy, the last copy and its warnings instead of the download reminder (ADR-167)', async () => {
    serveMe(aUser({ permissions: ['backup.manage'] }))
    const copied = { ...KEPT, id: 6, copyStatus: 'copied' }
    const notCopied = { ...KEPT, id: 7, fileName: 'ims-backup-20261021-010000-2.sql.gz', copyStatus: 'failed', copyFailureReason: 'The copy to IMS_BACKUP_COPY_DIR failed: AccessDeniedException' }
    server.use(
      http.get('/api/backups/status', () =>
        HttpResponse.json({
          configured: true,
          lastRun: notCopied,
          lastSuccessAt: notCopied.finishedAt,
          lastDownloadAt: null,
          downloadReminderDays: 7,
          copyConfigured: true,
          lastCopyAt: copied.finishedAt,
          warnings: ['COPY_FAILED'],
        }),
      ),
      http.get('/api/backups', () =>
        HttpResponse.json({ content: [notCopied, copied], page: { number: 0, size: 20, totalElements: 2, totalPages: 1 } }),
      ),
    )
    renderApp('/admin/backups')

    expect(await screen.findByText("La dernière sauvegarde n'a pas été copiée dans Google Drive.")).toBeInTheDocument()
    expect(screen.queryByText(/Aucune sauvegarde téléchargée/)).not.toBeInTheDocument()
    expect(screen.getByText('Dernière copie Google Drive')).toBeInTheDocument()
    expect(screen.getByText(/Chaque sauvegarde est copiée dans le dossier Google Drive/)).toBeInTheDocument()
    expect(await screen.findByRole('columnheader', { name: 'Copie Google Drive' })).toBeInTheDocument()
    expect(screen.getByText('Copiée')).toBeInTheDocument()
    expect(screen.getByText('The copy to IMS_BACKUP_COPY_DIR failed: AccessDeniedException')).toHaveAttribute('lang', 'en')
  })

  it('is hidden without backup.manage', async () => {
    serveMe(aUser({ permissions: ['report.sales'] }))
    renderApp('/admin/backups')
    expect(await screen.findByRole('heading', { name: 'Accès refusé' })).toBeInTheDocument()
  })
})

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { Pager } from '../../components/Pager'
import { problemMessage } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { backupDownloadUrl, useBackups, useBackupsRefresh, useBackupStatus, useRunBackup } from './backupApi'

function size(bytes: number | null): string {
  if (bytes === null) {
    return ''
  }
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} Ko` : `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} Mo`
}

/**
 * `/admin/backups` (ADR-144…146, ADR-167, frontend.md §4.11): the warnings (not configured, last run failed, no recent
 * backup; with a Google Drive copy folder, the last copy failed or none within a day, otherwise no download for
 * `backup_download_reminder_days`), Run now, the log with each copy's result and the download of a kept backup.
 */
export function BackupsPage() {
  const { t } = useTranslation()
  const [page, setPage] = useState(0)
  const status = useBackupStatus()
  const backups = useBackups(page)
  const run = useRunBackup()
  const refresh = useBackupsRefresh()

  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{t('admin.backups.title')}</h1>
      {status.data && (
        <>
          {status.data.warnings.map((w) => (
            <Alert key={w} tone={w === 'DOWNLOAD_OVERDUE' || w === 'NO_RECENT_COPY' ? 'warn' : 'danger'}>
              {t(`admin.backups.warnings.${w}`, { days: status.data.downloadReminderDays })}
            </Alert>
          ))}
          <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1 text-sm max-w-xl">
            <dt className="text-mute">{t('admin.backups.lastSuccess')}</dt>
            <dd>{status.data.lastSuccessAt ? formatDateTime(status.data.lastSuccessAt) : t('admin.backups.never')}</dd>
            {status.data.copyConfigured && (
              <>
                <dt className="text-mute">{t('admin.backups.lastCopy')}</dt>
                <dd>{status.data.lastCopyAt ? formatDateTime(status.data.lastCopyAt) : t('admin.backups.never')}</dd>
              </>
            )}
            <dt className="text-mute">{t('admin.backups.lastDownload')}</dt>
            <dd>{status.data.lastDownloadAt ? formatDateTime(status.data.lastDownloadAt) : t('admin.backups.never')}</dd>
          </dl>
        </>
      )}
      <div className="flex gap-3 items-center">
        <Button onClick={() => run.mutate()} disabled={run.isPending}>
          {run.isPending ? t('admin.backups.running') : t('admin.backups.runNow')}
        </Button>
        {run.data?.status === 'success' && <Alert tone="ok">{t('admin.backups.done', { file: run.data.fileName })}</Alert>}
        {run.data?.status === 'failed' && <Alert tone="danger">{t('admin.backups.failedPrefix')} <span lang="en">{run.data.failureReason}</span></Alert>}
        {run.error instanceof ApiError && <Alert tone="danger">{problemMessage(run.error.problem, t)}</Alert>}
      </div>
      <p className="text-sm text-mute max-w-3xl">
        {t(status.data?.copyConfigured ? 'admin.backups.offsiteCopy' : 'admin.backups.offsite')}
      </p>
      {backups.data && backups.data.content.length === 0 && <p className="text-mute">{t('admin.backups.empty')}</p>}
      {backups.data && backups.data.content.length > 0 && (
        <>
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2 px-2">{t('admin.backups.startedAt')}</th>
                <th className="text-start px-2">{t('admin.backups.trigger')}</th>
                <th className="text-start px-2">{t('admin.backups.status')}</th>
                <th className="text-start px-2">{t('admin.backups.file')}</th>
                <th className="text-end px-2">{t('admin.backups.size')}</th>
                {status.data?.copyConfigured && <th className="text-start px-2">{t('admin.backups.copy')}</th>}
                <th className="text-start px-2">{t('admin.backups.downloaded')}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {backups.data.content.map((b) => (
                <tr key={b.id} className="border-b border-line">
                  <td className="py-2 px-2">{formatDateTime(b.startedAt)}</td>
                  <td className="px-2">{t(`admin.backups.triggers.${b.trigger}`)}</td>
                  <td className="px-2">
                    {b.status === 'success'
                      ? b.removedAt
                        ? t('admin.backups.removed')
                        : t('admin.backups.statuses.success')
                      : t('admin.backups.statuses.failed')}
                  </td>
                  <td className="px-2">{b.status === 'success' ? <code>{b.fileName}</code> : <span className="text-danger" lang="en">
                        {b.failureReason}
                      </span>}</td>
                  <td className="text-end tabular-nums px-2">{size(b.sizeBytes)}</td>
                  {status.data?.copyConfigured && (
                    <td className="px-2">
                      {b.copyStatus === 'copied' && t('admin.backups.copies.copied')}
                      {b.copyStatus === 'failed' && (
                        <span className="text-danger">
                          {t('admin.backups.copies.failed')} : <span lang="en">{b.copyFailureReason}</span>
                        </span>
                      )}
                      {b.copyStatus === null && '—'}
                    </td>
                  )}
                  <td className="px-2">{b.lastDownloadedAt ? formatDateTime(b.lastDownloadedAt) : '—'}</td>
                  <td className="px-2">
                    {b.available && (
                      <a
                        href={backupDownloadUrl(b.id)}
                        download
                        onClick={() => window.setTimeout(() => void refresh(), 2000)}
                        className="h-8 px-3 inline-flex items-center rounded-ctl border border-control text-sm font-medium"
                      >
                        {t('admin.backups.download')}
                      </a>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={backups.data.page} onPage={setPage} />
        </>
      )}
    </section>
  )
}

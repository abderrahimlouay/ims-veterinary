import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { Page } from './api'

/** api.md §4.14 (ADR-144…146, ADR-167). */
export interface BackupRecord {
  id: number
  trigger: 'scheduled' | 'manual'
  startedBy: number | null
  status: 'success' | 'failed'
  fileName: string | null
  sizeBytes: number | null
  failureReason: string | null
  /** ADR-167: null when no copy folder is configured or the backup failed. */
  copyStatus: 'copied' | 'failed' | null
  copyFailureReason: string | null
  startedAt: string
  finishedAt: string
  removedAt: string | null
  lastDownloadedAt: string | null
  available: boolean
}

export type BackupWarning =
  | 'NOT_CONFIGURED'
  | 'LAST_FAILED'
  | 'NO_RECENT_BACKUP'
  | 'DOWNLOAD_OVERDUE'
  | 'COPY_FAILED'
  | 'NO_RECENT_COPY'

export interface BackupStatus {
  configured: boolean
  lastRun: BackupRecord | null
  lastSuccessAt: string | null
  lastDownloadAt: string | null
  downloadReminderDays: number
  copyConfigured: boolean
  lastCopyAt: string | null
  warnings: BackupWarning[]
}

const backupKeys = { all: ['admin', 'backups'] as const }

export function useBackups(page: number) {
  return useQuery({
    queryKey: [...backupKeys.all, 'list', page],
    queryFn: () => apiFetch<Page<BackupRecord>>(`/api/backups?page=${page}&size=20`),
    placeholderData: keepPreviousData,
  })
}

export function useBackupStatus() {
  return useQuery({ queryKey: [...backupKeys.all, 'status'], queryFn: () => apiFetch<BackupStatus>('/api/backups/status') })
}

/** Runs a backup now and waits for it (ADR-144); a failed dump comes back as a failed record. */
export function useRunBackup() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch<BackupRecord>('/api/backups', { method: 'POST' }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: backupKeys.all }),
  })
}

export function backupDownloadUrl(id: number): string {
  return `/api/backups/${id}/download`
}

/** Refreshes the log and status after a download (it sets the last download date). */
export function useBackupsRefresh() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: backupKeys.all })
}

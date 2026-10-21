import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import { apiFetch } from '../../api/client'
import { applyBusinessSettings } from '../../lib/business'

/** `GET /api/settings/public` (ADR-044): thresholds, default VAT and the business formatting settings. */
export interface PublicSettings {
  expiryAlertDays: number[]
  defaultVatRate: string
  currency: string
  locale: string
  timeZone: string
}

export const publicSettingsKey = ['settings', 'public'] as const

/** Loads the public settings for a logged-in user and applies the business formatting settings. */
export function usePublicSettings(enabled: boolean) {
  const query = useQuery({
    queryKey: publicSettingsKey,
    queryFn: () => apiFetch<PublicSettings>('/api/settings/public'),
    enabled,
    staleTime: 10 * 60_000,
  })
  useEffect(() => {
    if (query.data) {
      applyBusinessSettings(query.data)
    }
  }, [query.data])
  return query
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { TFunction } from 'i18next'
import { apiFetch } from '../../api/client'

/** The catalogue reference lists (api.md §4.3) and their i18n key for seeded codes (ADR-054). */
export const REFERENCE_LISTS = [
  { path: 'animal-species', i18nKey: 'species', hasCountry: false },
  { path: 'categories', i18nKey: 'categories', hasCountry: false },
  { path: 'dosage-forms', i18nKey: 'dosageForms', hasCountry: false },
  { path: 'active-ingredients', i18nKey: 'ingredients', hasCountry: false },
  { path: 'manufacturers', i18nKey: 'manufacturers', hasCountry: true },
] as const

export type ReferenceListPath = (typeof REFERENCE_LISTS)[number]['path']

export interface ReferenceItem {
  id: number
  name: string
  code: string | null
  country: string | null
}

/**
 * ADR-054: a seeded entry is shown in the current UI language through its code; an entry added by an
 * administrator is shown exactly as typed.
 */
export function referenceLabel(item: ReferenceItem, i18nKey: string, t: TFunction): string {
  return item.code === null ? item.name : t(`reference.${i18nKey}.${item.code}`, { defaultValue: item.name })
}

export const referenceKeys = { list: (path: ReferenceListPath) => ['catalog', 'reference', path] as const }

export function useReferenceList(path: ReferenceListPath) {
  return useQuery({
    queryKey: referenceKeys.list(path),
    queryFn: () => apiFetch<ReferenceItem[]>(`/api/${path}`),
    staleTime: 10 * 60_000,
  })
}

export function useSaveReference(path: ReferenceListPath) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, name, country }: { id?: number; name: string; country?: string | null }) =>
      apiFetch<ReferenceItem>(id === undefined ? `/api/${path}` : `/api/${path}/${id}`, {
        method: id === undefined ? 'POST' : 'PUT',
        json: { name, country: country ?? null },
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: referenceKeys.list(path) }),
  })
}

export function useDeleteReference(path: ReferenceListPath) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => apiFetch(`/api/${path}/${id}`, { method: 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: referenceKeys.list(path) }),
  })
}

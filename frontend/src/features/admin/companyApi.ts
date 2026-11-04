import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'

/** api.md §4.14: the company profile printed on documents (ADR-140); every save is a new version. */
export interface CompanyProfile {
  id: number
  legalName: string
  legalForm: string | null
  address: string | null
  wilaya: string | null
  phone: string | null
  email: string | null
  nif: string | null
  nis: string | null
  rc: string | null
  ai: string | null
  approvalNumber: string | null
  bankAccount: string | null
  footerText: string | null
  hasLogo: boolean
  effectiveFrom: string
  createdBy: number
}

export const COMPANY_FIELDS = [
  'legalName',
  'legalForm',
  'address',
  'wilaya',
  'phone',
  'email',
  'nif',
  'nis',
  'rc',
  'ai',
  'approvalNumber',
  'bankAccount',
  'footerText',
] as const

export type CompanyField = (typeof COMPANY_FIELDS)[number]

const KEY = ['company-profile'] as const

/** `null` while no version exists (204). */
export function useCompanyProfile() {
  return useQuery({ queryKey: KEY, queryFn: async () => (await apiFetch<CompanyProfile | undefined>('/api/company-profile')) ?? null })
}

/** The multipart body: filled fields only, trimmed; `logo` replaces the logo, else `keepLogo` keeps the current one. */
export function profileForm(values: Record<CompanyField, string>, logo: Blob | null, keepLogo: boolean): FormData {
  const form = new FormData()
  for (const field of COMPANY_FIELDS) {
    if (values[field].trim() !== '') {
      form.append(field, values[field].trim())
    }
  }
  if (logo !== null) {
    form.append('logo', logo, logo instanceof File ? logo.name : 'logo')
  }
  form.append('keepLogo', String(keepLogo))
  return form
}

/** Each save is a new version (ADR-140). */
export function useSaveCompanyProfile() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: ({ values, logo, keepLogo }: { values: Record<CompanyField, string>; logo: Blob | null; keepLogo: boolean }) =>
      apiFetch<CompanyProfile>('/api/company-profile', { method: 'POST', form: profileForm(values, logo, keepLogo) }),
    onSuccess: () => client.invalidateQueries({ queryKey: KEY }),
  })
}

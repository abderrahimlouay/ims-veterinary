import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { COMPANY_FIELDS, useCompanyProfile, useSaveCompanyProfile, type CompanyField, type CompanyProfile } from './companyApi'

const MAX_LOGO_BYTES = 256 * 1024
const LTR: ReadonlySet<CompanyField> = new Set(['phone', 'email', 'nif', 'nis', 'rc', 'ai', 'approvalNumber', 'bankAccount'])

/**
 * The company profile on `/admin/settings` (ADR-140, `settings.manage`): the identity printed on documents. Saving
 * adds a version; documents already issued keep printing the version in force at their issue.
 */
export function CompanyProfileSection() {
  const { t } = useTranslation()
  const profile = useCompanyProfile()
  if (profile.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  return <CompanyProfileForm key={profile.data?.id ?? 'none'} profile={profile.data ?? null} />
}

function CompanyProfileForm({ profile }: { profile: CompanyProfile | null }) {
  const { t } = useTranslation()
  const save = useSaveCompanyProfile()
  const [values, setValues] = useState<Record<CompanyField, string>>(
    () => Object.fromEntries(COMPANY_FIELDS.map((f) => [f, profile?.[f] ?? ''])) as Record<CompanyField, string>,
  )
  const [logo, setLogo] = useState<File | null>(null)
  const [keepLogo, setKeepLogo] = useState(true)
  const [errors, setErrors] = useState<Partial<Record<CompanyField | 'logo', string>>>({})
  const [problem, setProblem] = useState<Problem>()
  const [saved, setSaved] = useState(false)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setSaved(false)
    setProblem(undefined)
    const local: Partial<Record<CompanyField | 'logo', string>> = {}
    if (values.legalName.trim() === '') {
      local.legalName = t('fieldErrors.REQUIRED')
    }
    if (logo !== null && logo.size > MAX_LOGO_BYTES) {
      local.logo = t('admin.company.logoTooLarge')
    }
    setErrors(local)
    if (Object.keys(local).length > 0) {
      return
    }
    save.mutate(
      { values, logo, keepLogo: logo === null && keepLogo && profile?.hasLogo === true },
      {
        onSuccess: () => setSaved(true),
        onError: (error) => {
          if (!(error instanceof ApiError)) {
            return
          }
          const byField: Partial<Record<CompanyField | 'logo', string>> = {}
          for (const e of error.problem.errors) {
            byField[e.field as CompanyField | 'logo'] = e.field === 'logo' && e.code === 'SIZE' ? t('admin.company.logoTooLarge') : t(`fieldErrors.${e.code}`)
          }
          setErrors(byField)
          if (Object.keys(byField).length === 0) {
            setProblem(error.problem)
          }
        },
      },
    )
  }

  return (
    <section className="flex flex-col gap-3" aria-labelledby="company-profile">
      <h2 id="company-profile" className="text-lg font-semibold">
        {t('admin.company.title')}
      </h2>
      <p className="text-sm text-mute">
        {profile ? t('admin.company.version', { date: formatDateTime(profile.effectiveFrom) }) : t('admin.company.none')}
      </p>
      <p className="text-xs text-mute">{t('admin.company.hint')}</p>
      <form onSubmit={submit} noValidate className="flex flex-col gap-3">
        {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
        {saved && <Alert tone="ok">{t('admin.company.saved')}</Alert>}
        <div className="grid sm:grid-cols-2 gap-3">
          {COMPANY_FIELDS.map((field) => (
            <TextField
              key={field}
              label={t(`admin.company.fields.${field}`)}
              dir={LTR.has(field) ? 'ltr' : undefined}
              type={field === 'email' ? 'email' : undefined}
              value={values[field]}
              error={errors[field]}
              onChange={(e) => setValues((v) => ({ ...v, [field]: e.target.value }))}
            />
          ))}
        </div>
        <div className="flex flex-col gap-1">
          <label htmlFor="company-logo" className="text-sm font-medium">
            {t('admin.company.logo')}
          </label>
          {profile?.hasLogo && <img src={`/api/company-profile/logo?v=${profile.id}`} alt={t('admin.company.currentLogo')} className="h-16 w-auto self-start" />}
          <input
            id="company-logo"
            type="file"
            accept="image/png,image/jpeg"
            aria-invalid={errors.logo !== undefined}
            aria-describedby={errors.logo !== undefined ? 'company-logo-error' : undefined}
            onChange={(e) => setLogo(e.target.files?.[0] ?? null)}
            className="text-sm"
          />
          {errors.logo && (
            <p id="company-logo-error" className="text-sm text-danger">
              {errors.logo}
            </p>
          )}
          {profile?.hasLogo && logo === null && (
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" checked={keepLogo} onChange={(e) => setKeepLogo(e.target.checked)} />
              {t('admin.company.keepLogo')}
            </label>
          )}
        </div>
        <Button type="submit" disabled={save.isPending} className="self-start">
          {t('admin.company.save')}
        </Button>
      </form>
    </section>
  )
}

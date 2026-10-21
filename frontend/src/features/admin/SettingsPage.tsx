import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { useSettings, useUpdateSetting, type Setting } from './api'

/** Early, local checks mirroring ADR-044 formats; the server validates and normalises. */
const LOCAL_CHECKS: Record<string, (value: string) => boolean> = {
  expiry_alert_days: (v) => {
    const days = v.split(',').map((d) => Number(d.trim()))
    return days.length === 3 && days.every((d) => Number.isInteger(d) && d > 0) && days[0] > days[1] && days[1] > days[2]
  },
  default_vat_rate: (v) => /^\d{1,3}(\.\d{1,2})?$/.test(v.trim()) && Number(v) <= 100,
  login_max_failures: (v) => /^[1-9]\d*$/.test(v.trim()),
  login_window_minutes: (v) => /^[1-9]\d*$/.test(v.trim()),
  login_lockout_minutes: (v) => /^[1-9]\d*$/.test(v.trim()),
}

/** `/admin/settings` (FR-SEC-003, ADR-044): one form per setting; currency and time zone are read-only. */
export function SettingsPage() {
  const { t } = useTranslation()
  const settings = useSettings()
  if (!settings.data) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  return (
    <section className="flex flex-col gap-6 max-w-2xl">
      <h1 className="text-2xl font-semibold">{t('admin.settings.title')}</h1>
      {settings.data.map((s) => (
        <SettingRow key={`${s.key}-${s.updatedAt}`} setting={s} />
      ))}
    </section>
  )
}

function SettingRow({ setting }: { setting: Setting }) {
  const { t } = useTranslation()
  const update = useUpdateSetting()
  const [value, setValue] = useState(setting.value)
  const [localError, setLocalError] = useState<string>()
  const label = t(`admin.settings.keys.${setting.key}`, { defaultValue: setting.key })
  const hint = t(`admin.settings.hints.${setting.key}`, { defaultValue: '' })
  const serverError = update.error instanceof ApiError ? update.error.problem : undefined
  const fieldError =
    localError ??
    (serverError?.errors[0] ? t(`fieldErrors.${serverError.errors[0].code}`) : undefined)

  if (!setting.editable) {
    return (
      <div className="flex flex-col gap-1">
        <span className="text-sm font-medium">{label}</span>
        <span>
          <code>{setting.value}</code> <span className="text-sm text-mute">({t('admin.settings.readOnly')})</span>
        </span>
      </div>
    )
  }

  const save = () => {
    const check = LOCAL_CHECKS[setting.key]
    if (check !== undefined && !check(value)) {
      setLocalError(t('fieldErrors.INVALID_FORMAT'))
      return
    }
    setLocalError(undefined)
    update.mutate({ key: setting.key, value })
  }

  return (
    <form
      noValidate
      onSubmit={(e) => {
        e.preventDefault()
        save()
      }}
      className="flex flex-col gap-2"
    >
      <div className="flex items-end gap-3">
        <div className="flex-1">
          <TextField label={label} value={value} onChange={(e) => setValue(e.target.value)} error={fieldError} />
        </div>
        <Button type="submit" disabled={update.isPending || value === setting.value}>
          {t('admin.save')}
        </Button>
      </div>
      {hint !== '' && <p className="text-sm text-mute">{hint}</p>}
      {serverError && serverError.errors.length === 0 && <Alert tone="danger">{problemMessage(serverError, t)}</Alert>}
      <p className="text-xs text-mute">{t('admin.settings.updatedAt', { at: formatDateTime(setting.updatedAt) })}</p>
    </form>
  )
}

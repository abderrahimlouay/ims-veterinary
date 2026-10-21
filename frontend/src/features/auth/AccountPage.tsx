import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router'
import { Alert } from '../../components/Alert'
import { useCurrentUser } from './api'
import { ChangePasswordForm } from './ChangePasswordForm'

/** `/account`: profile and own password change; the forced change of ADR-039 happens here. */
export function AccountPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const { data: current } = useCurrentUser()
  if (!current) {
    return null
  }
  const forced = current.mustChangePassword
  return (
    <section className="flex flex-col gap-6">
      <h1 className="text-2xl font-semibold">{t('auth.account.title')}</h1>
      {forced && <Alert tone="danger">{t('auth.account.mustChange')}</Alert>}
      <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
        <dt className="text-mute">{t('auth.account.username')}</dt>
        <dd>{current.user.username}</dd>
        <dt className="text-mute">{t('auth.account.fullName')}</dt>
        <dd>{current.user.fullName}</dd>
        <dt className="text-mute">{t('auth.account.email')}</dt>
        <dd>{current.user.email ?? '—'}</dd>
        <dt className="text-mute">{t('auth.account.role')}</dt>
        <dd>{current.user.role}</dd>
      </dl>
      <ChangePasswordForm onChanged={forced ? () => void navigate('/', { replace: true }) : undefined} />
    </section>
  )
}

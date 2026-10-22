import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { useNavigate, useParams } from 'react-router'
import { z } from 'zod'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { PasswordInput } from '../../components/PasswordInput'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatDateTime } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import {
  useCreateUser,
  useResetPassword,
  useRoles,
  useSetUserActive,
  useUpdateUser,
  useUser,
  type UserDetail,
} from './api'
import { placeServerErrors } from '../../lib/formErrors'

const editSchema = z.object({
  fullName: z.string().trim().min(1, 'validation.required').max(150, 'validation.tooLong'),
  email: z.union([z.literal(''), z.email('fieldErrors.EMAIL').max(150, 'validation.tooLong')]),
  roleId: z.string().min(1, 'validation.required'),
})
const createSchema = editSchema.extend({
  username: z.string().trim().min(1, 'validation.required').max(60, 'validation.tooLong'),
  initialPassword: z.string().min(12, 'fieldErrors.TOO_SHORT'),
})
type CreateForm = z.infer<typeof createSchema>
type EditForm = z.infer<typeof editSchema>

function useFieldError() {
  const { t } = useTranslation()
  return (message: string | undefined) => (message === undefined ? undefined : t(message, { min: 12 }))
}

/** `/admin/users/new` (ADR-045: the user must change the initial password at first login). */
export function NewUserPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const roles = useRoles()
  const create = useCreateUser()
  const fieldError = useFieldError()
  const [problem, setProblem] = useState<ApiError['problem']>()
  const { register, handleSubmit, formState, setError } = useForm<CreateForm>({
    resolver: zodResolver(createSchema),
    defaultValues: { username: '', fullName: '', email: '', roleId: '', initialPassword: '' },
  })

  const onSubmit = handleSubmit((v) => {
    setProblem(undefined)
    create.mutate(
      { username: v.username, fullName: v.fullName, email: v.email || null, roleId: Number(v.roleId), initialPassword: v.initialPassword },
      {
        onSuccess: (user) => void navigate(`/admin/users/${user.id}`, { replace: true }),
        onError: (error) =>
          setProblem(placeServerErrors(error, ['username', 'email', 'roleId', 'initialPassword'], setError)),
      },
    )
  })

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4 max-w-md">
      <h1 className="text-2xl font-semibold">{t('admin.users.new')}</h1>
      {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
      <TextField label={t('admin.users.username')} error={fieldError(formState.errors.username?.message)} {...register('username')} />
      <TextField label={t('admin.users.fullName')} error={fieldError(formState.errors.fullName?.message)} {...register('fullName')} />
      <TextField label={t('admin.users.email')} type="email" error={fieldError(formState.errors.email?.message)} {...register('email')} />
      <SelectField
        label={t('admin.users.role')}
        error={fieldError(formState.errors.roleId?.message)}
        options={[{ value: '', label: '—' }, ...(roles.data ?? []).map((r) => ({ value: String(r.id), label: r.name }))]}
        {...register('roleId')}
      />
      <PasswordInput
        label={t('admin.users.initialPassword')}
        autoComplete="new-password"
        error={fieldError(formState.errors.initialPassword?.message)}
        {...register('initialPassword')}
      />
      <p className="text-sm text-mute">{t('admin.users.initialPasswordHint')}</p>
      <Button type="submit" disabled={create.isPending}>
        {t('admin.save')}
      </Button>
    </form>
  )
}

/** `/admin/users/:id`: view (`user.read`), edit and actions (`user.manage`). */
export function UserDetailPage() {
  const { t } = useTranslation()
  const id = Number(useParams().id)
  const user = useUser(id)
  if (user.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (user.error instanceof ApiError) {
    return <Alert tone="danger">{problemMessage(user.error.problem, t)}</Alert>
  }
  return user.data ? <UserDetailView key={user.data.version} user={user.data} /> : null
}

function UserDetailView({ user }: { user: UserDetail }) {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const canManage = hasPermission(current, 'user.manage')
  const roles = useRoles()
  const updateUser = useUpdateUser(user.id)
  const setActive = useSetUserActive(user.id)
  const reset = useResetPassword(user.id)
  const fieldError = useFieldError()
  const [problem, setProblem] = useState<ApiError['problem']>()
  const [saved, setSaved] = useState(false)
  const { register, handleSubmit, formState, setError, reset: resetForm } = useForm<EditForm>({
    resolver: zodResolver(editSchema),
    defaultValues: { fullName: user.fullName, email: user.email ?? '', roleId: String(user.role.id) },
  })
  useEffect(() => {
    resetForm({ fullName: user.fullName, email: user.email ?? '', roleId: String(user.role.id) })
  }, [user, resetForm])

  const onSubmit = handleSubmit((v) => {
    setProblem(undefined)
    setSaved(false)
    updateUser.mutate(
      { fullName: v.fullName, email: v.email || null, roleId: Number(v.roleId), version: user.version },
      {
        onSuccess: () => setSaved(true),
        onError: (error) => setProblem(placeServerErrors(error, ['fullName', 'email', 'roleId'], setError)),
      },
    )
  })
  const actionProblem = [setActive.error, reset.error].find((e) => e instanceof ApiError) as ApiError | undefined
  const isSelf = current?.user.id === user.id

  return (
    <section className="flex flex-col gap-6 max-w-xl">
      <h1 className="text-2xl font-semibold">{user.username}</h1>
      <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
        <dt className="text-mute">{t('admin.users.status')}</dt>
        <dd>{user.active ? t('admin.users.active') : t('admin.users.inactive')}</dd>
        <dt className="text-mute">{t('admin.users.lastLogin')}</dt>
        <dd>{user.lastLoginAt ? formatDateTime(user.lastLoginAt) : '—'}</dd>
        <dt className="text-mute">{t('admin.users.createdAt')}</dt>
        <dd>{formatDateTime(user.createdAt)}</dd>
        {user.mustChangePassword && (
          <>
            <dt className="text-mute">{t('admin.users.password')}</dt>
            <dd>{t('admin.users.mustChange')}</dd>
          </>
        )}
      </dl>
      <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
        {saved && <Alert tone="ok">{t('admin.saved')}</Alert>}
        {problem && (
          <Alert tone="danger">
            {problem.code === 'CONCURRENT_MODIFICATION' ? t('admin.stale') : problemMessage(problem, t)}
          </Alert>
        )}
        <TextField label={t('admin.users.fullName')} disabled={!canManage} error={fieldError(formState.errors.fullName?.message)} {...register('fullName')} />
        <TextField label={t('admin.users.email')} type="email" disabled={!canManage} error={fieldError(formState.errors.email?.message)} {...register('email')} />
        <SelectField
          label={t('admin.users.role')}
          disabled={!canManage}
          error={fieldError(formState.errors.roleId?.message)}
          options={(roles.data ?? [{ id: user.role.id, name: user.role.name }]).map((r) => ({ value: String(r.id), label: r.name }))}
          {...register('roleId')}
        />
        {canManage && (
          <Button type="submit" disabled={updateUser.isPending}>
            {t('admin.save')}
          </Button>
        )}
      </form>
      {canManage && (
        <div className="flex flex-col gap-3">
          {actionProblem && <Alert tone="danger">{problemMessage(actionProblem.problem, t)}</Alert>}
          {reset.data && (
            <Alert tone="ok">
              <div className="flex flex-col gap-2 text-ink">
                <PasswordInput
                  label={t('admin.users.temporaryPassword')}
                  readOnly
                  copyable
                  autoComplete="off"
                  value={reset.data.temporaryPassword}
                  data-testid="temporary-password"
                />
                <p>{t('admin.users.temporaryPasswordOnce')}</p>
              </div>
            </Alert>
          )}
          <div className="flex gap-3">
            {!isSelf && (
              <Button variant="secondary" disabled={setActive.isPending} onClick={() => setActive.mutate(!user.active)}>
                {user.active ? t('admin.users.deactivate') : t('admin.users.activate')}
              </Button>
            )}
            <Button variant="secondary" disabled={reset.isPending} onClick={() => reset.mutate()}>
              {t('admin.users.resetPassword')}
            </Button>
          </div>
        </div>
      )}
    </section>
  )
}

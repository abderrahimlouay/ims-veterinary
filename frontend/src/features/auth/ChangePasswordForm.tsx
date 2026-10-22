import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { z } from 'zod'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { PasswordInput } from '../../components/PasswordInput'
import { problemMessage } from '../../lib/errors'
import { useChangePassword } from './api'

/** Mirrors the server policy for early feedback only (ADR-039); the server decides. */
const MIN_LENGTH = 12

const schema = z
  .object({
    currentPassword: z.string().min(1, 'validation.required'),
    newPassword: z.string().min(MIN_LENGTH, 'auth.password.TOO_SHORT'),
    confirmPassword: z.string().min(1, 'validation.required'),
  })
  .refine((v) => v.newPassword === v.confirmPassword, {
    message: 'auth.password.mismatch',
    path: ['confirmPassword'],
  })

type PasswordForm = z.infer<typeof schema>
const SERVER_FIELDS: ReadonlySet<string> = new Set(['currentPassword', 'newPassword'])

export function ChangePasswordForm({ onChanged }: { onChanged?: () => void }) {
  const { t } = useTranslation()
  const change = useChangePassword()
  const [done, setDone] = useState(false)
  const { register, handleSubmit, formState, setError, reset } = useForm<PasswordForm>({
    resolver: zodResolver(schema),
  })

  const onSubmit = handleSubmit(({ currentPassword, newPassword }) => {
    setDone(false)
    change.mutate(
      { currentPassword, newPassword },
      {
        onSuccess: () => {
          reset()
          setDone(true)
          onChanged?.()
        },
        onError: (error) => {
          if (error instanceof ApiError) {
            for (const fieldError of error.problem.errors) {
              if (SERVER_FIELDS.has(fieldError.field)) {
                setError(fieldError.field as 'currentPassword' | 'newPassword', {
                  message: `auth.password.${fieldError.code}`,
                })
              }
            }
          }
        },
      },
    )
  })

  const fieldError = (name: keyof PasswordForm) => {
    const message = formState.errors[name]?.message
    return message === undefined ? undefined : t(message, { min: MIN_LENGTH })
  }
  const serverProblem =
    change.error instanceof ApiError && change.error.problem.errors.length === 0 ? change.error.problem : undefined

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4 max-w-sm">
      <h2 className="text-lg font-semibold">{t('auth.password.title')}</h2>
      {done && <Alert tone="ok">{t('auth.password.changed')}</Alert>}
      {serverProblem && <Alert tone="danger">{problemMessage(serverProblem, t)}</Alert>}
      <PasswordInput
        label={t('auth.password.current')}
        autoComplete="current-password"
        error={fieldError('currentPassword')}
        {...register('currentPassword')}
      />
      <PasswordInput
        label={t('auth.password.new')}
        autoComplete="new-password"
        error={fieldError('newPassword')}
        {...register('newPassword')}
      />
      <PasswordInput
        label={t('auth.password.confirm')}
        autoComplete="new-password"
        error={fieldError('confirmPassword')}
        {...register('confirmPassword')}
      />
      <Button type="submit" disabled={change.isPending}>
        {t('auth.password.submit')}
      </Button>
    </form>
  )
}

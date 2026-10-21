import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { useTranslation } from 'react-i18next'
import { Navigate, useNavigate, useSearchParams } from 'react-router'
import { z } from 'zod'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { useCurrentUser, useLogin } from './api'
import { safeRedirect } from './redirect'

const schema = z.object({
  username: z.string().trim().min(1, 'validation.required').max(60, 'validation.tooLong'),
  password: z.string().min(1, 'validation.required'),
})

type LoginForm = z.infer<typeof schema>

/** `/login` (frontend.md §3); after login the user returns to the page they asked for. */
export function LoginPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const target = safeRedirect(params.get('redirect'))
  const current = useCurrentUser()
  const login = useLogin()
  const { register, handleSubmit, formState } = useForm<LoginForm>({ resolver: zodResolver(schema) })

  if (current.data) {
    return <Navigate to={current.data.mustChangePassword ? '/account' : target} replace />
  }

  const onSubmit = handleSubmit((values) =>
    login.mutate(values, {
      onSuccess: (user) => void navigate(user.mustChangePassword ? '/account' : target, { replace: true }),
    }),
  )

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold">{t('auth.login.title')}</h1>
      {login.error instanceof ApiError && <Alert tone="danger">{problemMessage(login.error.problem, t)}</Alert>}
      <TextField
        label={t('auth.login.username')}
        autoComplete="username"
        autoFocus
        error={formState.errors.username && t(formState.errors.username.message ?? 'validation.required')}
        {...register('username')}
      />
      <TextField
        label={t('auth.login.password')}
        type="password"
        autoComplete="current-password"
        error={formState.errors.password && t(formState.errors.password.message ?? 'validation.required')}
        {...register('password')}
      />
      <Button type="submit" disabled={login.isPending}>
        {t('auth.login.submit')}
      </Button>
    </form>
  )
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../api/client'

/** `CurrentUser` of api.md §4.1, returned by login and `me`. */
export interface CurrentUser {
  user: { id: number; username: string; fullName: string; email: string | null; role: string }
  permissions: string[]
  mustChangePassword: boolean
}

export const authKeys = { me: ['auth', 'me'] as const }

/** The session's user, or `null` when nobody is logged in. */
export async function fetchCurrentUser(): Promise<CurrentUser | null> {
  try {
    return await apiFetch<CurrentUser>('/api/auth/me')
  } catch (error) {
    if (error instanceof ApiError && error.problem.code === 'AUTH_REQUIRED') {
      return null
    }
    throw error
  }
}

export function useCurrentUser() {
  return useQuery({ queryKey: authKeys.me, queryFn: fetchCurrentUser, staleTime: 5 * 60_000 })
}

/** Permission codes only drive what is shown; the backend enforces them (BR-SEC-002). */
export function hasPermission(user: CurrentUser | null | undefined, code: string): boolean {
  return user?.permissions.includes(code) ?? false
}

export function useLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (credentials: { username: string; password: string }) =>
      apiFetch<CurrentUser>('/api/auth/login', { method: 'POST', json: credentials }),
    onSuccess: (user) => queryClient.setQueryData(authKeys.me, user),
  })
}

/** Logout clears every cached query (frontend.md §12), then marks the session as ended. */
export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch('/api/auth/logout', { method: 'POST' }),
    onSettled: () => {
      queryClient.clear()
      queryClient.setQueryData(authKeys.me, null)
    },
  })
}

export function useChangePassword() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (passwords: { currentPassword: string; newPassword: string }) =>
      apiFetch('/api/auth/change-password', { method: 'POST', json: passwords }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: authKeys.me }),
  })
}

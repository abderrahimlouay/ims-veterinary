import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { I18nextProvider } from 'react-i18next'
import { ApiError } from '../api/client'
import { authKeys } from '../features/auth'
import { i18n } from '../lib/i18n'

/**
 * frontend.md §9: an expired session anywhere marks the user as logged out, so the guard sends them to the
 * login page with a return target; a pending forced password change re-reads the current user.
 */
function onApiError(client: QueryClient, error: unknown): void {
  if (!(error instanceof ApiError)) {
    return
  }
  if (error.problem.code === 'AUTH_REQUIRED') {
    client.setQueryData(authKeys.me, null)
  } else if (error.problem.code === 'PASSWORD_CHANGE_REQUIRED') {
    void client.invalidateQueries({ queryKey: authKeys.me })
  }
}

function createQueryClient(): QueryClient {
  const client: QueryClient = new QueryClient({
    queryCache: new QueryCache({ onError: (error) => onApiError(client, error) }),
    mutationCache: new MutationCache({ onError: (error) => onApiError(client, error) }),
    defaultOptions: {
      queries: { retry: 1, refetchOnWindowFocus: false },
      mutations: { retry: false }, // frontend.md §1: no retries for mutations
    },
  })
  return client
}

export function AppProviders({ children }: { children: ReactNode }) {
  const [queryClient] = useState(createQueryClient)
  return (
    <I18nextProvider i18n={i18n}>
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    </I18nextProvider>
  )
}

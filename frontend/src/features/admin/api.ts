import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../api/client'
import type { PageMetadata } from '../../components/Pager'

export interface Page<T> {
  content: T[]
  page: PageMetadata
}

/** api.md §4.2 (ADR-045). */
export interface UserSummary {
  id: number
  username: string
  fullName: string
  email: string | null
  role: { id: number; name: string }
  active: boolean
  mustChangePassword: boolean
  lastLoginAt: string | null
}

export interface UserDetail extends UserSummary {
  createdAt: string
  version: number
}

export interface RoleDetail {
  id: number
  name: string
  description: string | null
  permissions: string[]
}

export interface PermissionSummary {
  code: string
  description: string | null
}

/** api.md §4.14 (ADR-044). */
export interface Setting {
  key: string
  value: string
  description: string | null
  editable: boolean
  updatedAt: string
}

/** api.md §4.14 (ADR-046): `username` names the acting user; all user fields are null for system actions. */
export interface AuditEntry {
  id: number
  userId: number | null
  username: string | null
  userFullName: string | null
  action: string
  entity: string
  entityId: number | null
  oldValues: Record<string, unknown> | null
  newValues: Record<string, unknown> | null
  ipAddress: string | null
  createdAt: string
}

export interface UserFilter {
  q: string
  roleId: string
  active: string
  page: number
}

export interface AuditFilter {
  entity: string
  action: string
  from: string
  to: string
  page: number
}

const PAGE_SIZE = 20

function query(params: Record<string, string | number>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== '') {
      search.set(key, String(value))
    }
  }
  return search.toString()
}

export const adminKeys = {
  users: ['admin', 'users'] as const,
  user: (id: number) => ['admin', 'users', id] as const,
  roles: ['admin', 'roles'] as const,
  permissions: ['admin', 'permissions'] as const,
  settings: ['admin', 'settings'] as const,
  audit: ['admin', 'audit'] as const,
}

export function useUsers(filter: UserFilter) {
  return useQuery({
    queryKey: [...adminKeys.users, filter],
    queryFn: () =>
      apiFetch<Page<UserSummary>>(
        `/api/users?${query({ q: filter.q, roleId: filter.roleId, active: filter.active, page: filter.page, size: PAGE_SIZE })}`,
      ),
    placeholderData: keepPreviousData,
  })
}

export function useUser(id: number) {
  return useQuery({ queryKey: adminKeys.user(id), queryFn: () => apiFetch<UserDetail>(`/api/users/${id}`) })
}

function useUserMutation<V>(mutationFn: (variables: V) => Promise<UserDetail>) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: (user) => {
      queryClient.setQueryData(adminKeys.user(user.id), user)
      void queryClient.invalidateQueries({ queryKey: adminKeys.users, exact: false })
    },
  })
}

export function useCreateUser() {
  return useUserMutation(
    (user: { username: string; fullName: string; email: string | null; roleId: number; initialPassword: string }) =>
      apiFetch<UserDetail>('/api/users', { method: 'POST', json: user }),
  )
}

export function useUpdateUser(id: number) {
  return useUserMutation((changes: { fullName: string; email: string | null; roleId: number; version: number }) =>
    apiFetch<UserDetail>(`/api/users/${id}`, { method: 'PUT', json: changes }),
  )
}

export function useSetUserActive(id: number) {
  return useUserMutation((active: boolean) =>
    apiFetch<UserDetail>(`/api/users/${id}/${active ? 'activate' : 'deactivate'}`, { method: 'POST' }),
  )
}

export function useResetPassword(id: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch<{ temporaryPassword: string }>(`/api/users/${id}/reset-password`, { method: 'POST' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminKeys.user(id) }),
  })
}

export function useRoles() {
  return useQuery({ queryKey: adminKeys.roles, queryFn: () => apiFetch<RoleDetail[]>('/api/roles') })
}

export function usePermissions() {
  return useQuery({
    queryKey: adminKeys.permissions,
    queryFn: () => apiFetch<PermissionSummary[]>('/api/permissions'),
    staleTime: Infinity,
  })
}

export function useReplaceRolePermissions() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ roleId, codes }: { roleId: number; codes: string[] }) =>
      apiFetch<RoleDetail>(`/api/roles/${roleId}/permissions`, { method: 'PUT', json: { permissionCodes: codes } }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: adminKeys.roles }),
  })
}

export function useSettings() {
  return useQuery({ queryKey: adminKeys.settings, queryFn: () => apiFetch<Setting[]>('/api/settings') })
}

export function useUpdateSetting() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ key, value }: { key: string; value: string }) =>
      apiFetch<Setting>(`/api/settings/${encodeURIComponent(key)}`, { method: 'PUT', json: { value } }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.settings })
      void queryClient.invalidateQueries({ queryKey: ['settings', 'public'] })
    },
  })
}

export function useAuditLog(filter: AuditFilter) {
  return useQuery({
    queryKey: [...adminKeys.audit, filter],
    queryFn: () =>
      apiFetch<Page<AuditEntry>>(
        `/api/audit-logs?${query({ entity: filter.entity, action: filter.action, from: filter.from, to: filter.to, page: filter.page, size: PAGE_SIZE })}`,
      ),
    placeholderData: keepPreviousData,
  })
}

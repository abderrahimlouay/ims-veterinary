import type { RouteObject } from 'react-router'
import { AuditPage, NewUserPage, RolesPage, SettingsPage, UserDetailPage, UsersPage } from '../features/admin'
import { ReferencesPage } from '../features/catalog'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { AccountPage, LoginPage } from '../features/auth'
import { RequireAuth, RequirePermission } from './guards'
import { AppLayout } from './layouts/AppLayout'
import { AuthLayout } from './layouts/AuthLayout'
import { NotFoundPage } from './NotFoundPage'

/** Route map (frontend.md §3.1); feature routes are added phase by phase. */
export const routes: RouteObject[] = [
  {
    element: <AuthLayout />,
    children: [{ path: '/login', element: <LoginPage /> }],
  },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'account', element: <AccountPage /> },
      { path: 'catalog/references', element: <RequirePermission code="product.read"><ReferencesPage /></RequirePermission> },
      { path: 'admin/users', element: <RequirePermission code="user.read"><UsersPage /></RequirePermission> },
      { path: 'admin/users/new', element: <RequirePermission code="user.manage"><NewUserPage /></RequirePermission> },
      { path: 'admin/users/:id', element: <RequirePermission code="user.read"><UserDetailPage /></RequirePermission> },
      { path: 'admin/roles', element: <RequirePermission code="role.manage"><RolesPage /></RequirePermission> },
      { path: 'admin/settings', element: <RequirePermission code="settings.manage"><SettingsPage /></RequirePermission> },
      { path: 'admin/audit', element: <RequirePermission code="audit.read"><AuditPage /></RequirePermission> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]

import type { RouteObject } from 'react-router'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { AccountPage, LoginPage } from '../features/auth'
import { RequireAuth } from './guards'
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
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]

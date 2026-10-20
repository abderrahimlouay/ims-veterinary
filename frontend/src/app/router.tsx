import type { RouteObject } from 'react-router'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { AppLayout } from './layouts/AppLayout'
import { NotFoundPage } from './NotFoundPage'

/** Route map (frontend.md §3.1); feature routes are added phase by phase. */
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]

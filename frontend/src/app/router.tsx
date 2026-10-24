import type { RouteObject } from 'react-router'
import { AuditPage, NewUserPage, RolesPage, SettingsPage, UserDetailPage, UsersPage } from '../features/admin'
import { NewProductPage, ProductDetailPage, ProductsPage, ReferencesPage } from '../features/catalog'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import {
  AdjustmentPage,
  CountPage,
  CountsPage,
  IntegrityPage,
  LotDetailPage,
  LotsPage,
  NewCountPage,
  OpeningStockPage,
  ReorderPage,
  StockPage,
} from '../features/inventory'
import { AccountPage, LoginPage } from '../features/auth'
import {
  EditOrderPage,
  NewOrderPage,
  NewReceiptPage,
  NewSupplierPage,
  NewSupplierReturnPage,
  OrderDetailPage,
  PurchaseOrdersPage,
  ReceiptDetailPage,
  ReceiptsPage,
  SupplierPage,
  SupplierReturnPage,
  SupplierReturnsPage,
  SuppliersPage,
} from '../features/purchasing'
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
      { path: 'products', element: <RequirePermission code="product.read"><ProductsPage /></RequirePermission> },
      { path: 'products/new', element: <RequirePermission code="product.create"><NewProductPage /></RequirePermission> },
      { path: 'products/:id', element: <RequirePermission code="product.read"><ProductDetailPage /></RequirePermission> },
      { path: 'lots', element: <RequirePermission code="lot.read"><LotsPage /></RequirePermission> },
      { path: 'lots/:id', element: <RequirePermission code="lot.read"><LotDetailPage /></RequirePermission> },
      { path: 'stock', element: <RequirePermission code="stock.read"><StockPage /></RequirePermission> },
      { path: 'stock/adjustments/new', element: <RequirePermission code="stock.adjust"><AdjustmentPage /></RequirePermission> },
      { path: 'stock/opening-stock/new', element: <RequirePermission code="stock.adjust"><OpeningStockPage /></RequirePermission> },
      { path: 'stock/integrity', element: <RequirePermission code="audit.read"><IntegrityPage /></RequirePermission> },
      { path: 'inventory-counts', element: <RequirePermission code="inventory.count"><CountsPage /></RequirePermission> },
      { path: 'inventory-counts/new', element: <RequirePermission code="inventory.count"><NewCountPage /></RequirePermission> },
      { path: 'inventory-counts/:id', element: <RequirePermission code="inventory.count"><CountPage /></RequirePermission> },
      { path: 'reorder', element: <RequirePermission code="reorder.read"><ReorderPage /></RequirePermission> },
      { path: 'suppliers', element: <RequirePermission code="supplier.read"><SuppliersPage /></RequirePermission> },
      { path: 'suppliers/new', element: <RequirePermission code="supplier.manage"><NewSupplierPage /></RequirePermission> },
      { path: 'suppliers/:id', element: <RequirePermission code="supplier.read"><SupplierPage /></RequirePermission> },
      { path: 'purchase-orders', element: <RequirePermission code="purchase.read"><PurchaseOrdersPage /></RequirePermission> },
      { path: 'purchase-orders/new', element: <RequirePermission code="purchase.create"><NewOrderPage /></RequirePermission> },
      { path: 'purchase-orders/:id', element: <RequirePermission code="purchase.read"><OrderDetailPage /></RequirePermission> },
      { path: 'purchase-orders/:id/edit', element: <RequirePermission code="purchase.create"><EditOrderPage /></RequirePermission> },
      { path: 'goods-receipts', element: <RequirePermission code="receipt.read"><ReceiptsPage /></RequirePermission> },
      { path: 'goods-receipts/new', element: <RequirePermission code="receipt.create"><NewReceiptPage /></RequirePermission> },
      { path: 'goods-receipts/:id', element: <RequirePermission code="receipt.read"><ReceiptDetailPage /></RequirePermission> },
      { path: 'supplier-returns', element: <RequirePermission code="return.read"><SupplierReturnsPage /></RequirePermission> },
      { path: 'supplier-returns/new', element: <RequirePermission code="return.supplier.create"><NewSupplierReturnPage /></RequirePermission> },
      { path: 'supplier-returns/:id', element: <RequirePermission code="return.read"><SupplierReturnPage /></RequirePermission> },
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

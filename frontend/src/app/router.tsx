import { lazy, type ComponentType } from 'react'
import type { RouteObject } from 'react-router'
import { DashboardPage } from '../features/dashboard/DashboardPage'
import { AccountPage, LoginPage } from '../features/auth'
import { RequireAuth, RequirePermission } from './guards'
import { AppLayout } from './layouts/AppLayout'
import { AuthLayout } from './layouts/AuthLayout'
import { PosLayout } from './layouts/PosLayout'
import { NotFoundPage } from './NotFoundPage'

/**
 * A page loaded with its feature's code only when first opened (frontend.md §7, Phase 12: the single bundle was
 * about 1 MB); login, account and the dashboard stay in the main bundle.
 */
function page<M, K extends keyof M>(load: () => Promise<M>, name: K) {
  return lazy(async () => ({ default: (await load())[name] as ComponentType }))
}

const AuditPage = page(() => import('../features/admin'), 'AuditPage')
const BackupsPage = page(() => import('../features/admin'), 'BackupsPage')
const NewUserPage = page(() => import('../features/admin'), 'NewUserPage')
const RolesPage = page(() => import('../features/admin'), 'RolesPage')
const SettingsPage = page(() => import('../features/admin'), 'SettingsPage')
const UserDetailPage = page(() => import('../features/admin'), 'UserDetailPage')
const UsersPage = page(() => import('../features/admin'), 'UsersPage')
const ReportPage = page(() => import('../features/reports'), 'ReportPage')
const ReportsPage = page(() => import('../features/reports'), 'ReportsPage')
const NewProductPage = page(() => import('../features/catalog'), 'NewProductPage')
const ProductDetailPage = page(() => import('../features/catalog'), 'ProductDetailPage')
const ProductsPage = page(() => import('../features/catalog'), 'ProductsPage')
const ReferencesPage = page(() => import('../features/catalog'), 'ReferencesPage')
const CustomerCategoriesPage = page(() => import('../features/customers'), 'CustomerCategoriesPage')
const CustomerPage = page(() => import('../features/customers'), 'CustomerPage')
const CustomersPage = page(() => import('../features/customers'), 'CustomersPage')
const NewCustomerPage = page(() => import('../features/customers'), 'NewCustomerPage')
const CashSessionPage = page(() => import('../features/finance'), 'CashSessionPage')
const CashSessionsPage = page(() => import('../features/finance'), 'CashSessionsPage')
const NewSupplierInvoicePage = page(() => import('../features/finance'), 'NewSupplierInvoicePage')
const SupplierInvoicePage = page(() => import('../features/finance'), 'SupplierInvoicePage')
const SupplierInvoicesPage = page(() => import('../features/finance'), 'SupplierInvoicesPage')
const AdjustmentPage = page(() => import('../features/inventory'), 'AdjustmentPage')
const CountPage = page(() => import('../features/inventory'), 'CountPage')
const CountsPage = page(() => import('../features/inventory'), 'CountsPage')
const IntegrityPage = page(() => import('../features/inventory'), 'IntegrityPage')
const LotDetailPage = page(() => import('../features/inventory'), 'LotDetailPage')
const LotsPage = page(() => import('../features/inventory'), 'LotsPage')
const NewCountPage = page(() => import('../features/inventory'), 'NewCountPage')
const OpeningStockPage = page(() => import('../features/inventory'), 'OpeningStockPage')
const ReorderPage = page(() => import('../features/inventory'), 'ReorderPage')
const StockPage = page(() => import('../features/inventory'), 'StockPage')
const EditOrderPage = page(() => import('../features/purchasing'), 'EditOrderPage')
const NewOrderPage = page(() => import('../features/purchasing'), 'NewOrderPage')
const NewReceiptPage = page(() => import('../features/purchasing'), 'NewReceiptPage')
const NewSupplierPage = page(() => import('../features/purchasing'), 'NewSupplierPage')
const NewSupplierReturnPage = page(() => import('../features/purchasing'), 'NewSupplierReturnPage')
const OrderDetailPage = page(() => import('../features/purchasing'), 'OrderDetailPage')
const PurchaseOrdersPage = page(() => import('../features/purchasing'), 'PurchaseOrdersPage')
const ReceiptDetailPage = page(() => import('../features/purchasing'), 'ReceiptDetailPage')
const ReceiptsPage = page(() => import('../features/purchasing'), 'ReceiptsPage')
const SupplierPage = page(() => import('../features/purchasing'), 'SupplierPage')
const SupplierReturnPage = page(() => import('../features/purchasing'), 'SupplierReturnPage')
const SupplierReturnsPage = page(() => import('../features/purchasing'), 'SupplierReturnsPage')
const SuppliersPage = page(() => import('../features/purchasing'), 'SuppliersPage')
const CustomerReturnPage = page(() => import('../features/sales'), 'CustomerReturnPage')
const CustomerReturnsPage = page(() => import('../features/sales'), 'CustomerReturnsPage')
const NewCustomerReturnPage = page(() => import('../features/sales'), 'NewCustomerReturnPage')
const PosPage = page(() => import('../features/sales'), 'PosPage')
const SaleDetailPage = page(() => import('../features/sales'), 'SaleDetailPage')
const SalesPage = page(() => import('../features/sales'), 'SalesPage')

/** Route map (frontend.md §3.1); feature routes are added phase by phase. */
export const routes: RouteObject[] = [
  {
    element: <AuthLayout />,
    children: [{ path: '/login', element: <LoginPage /> }],
  },
  {
    path: '/pos',
    element: (
      <RequireAuth>
        <PosLayout />
      </RequireAuth>
    ),
    children: [{ index: true, element: <RequirePermission code="sale.create"><PosPage /></RequirePermission> }],
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
      { path: 'supplier-invoices', element: <RequirePermission code="supplierinvoice.manage"><SupplierInvoicesPage /></RequirePermission> },
      { path: 'supplier-invoices/new', element: <RequirePermission code="supplierinvoice.manage"><NewSupplierInvoicePage /></RequirePermission> },
      { path: 'supplier-invoices/:id', element: <RequirePermission code="supplierinvoice.manage"><SupplierInvoicePage /></RequirePermission> },
      { path: 'cash-sessions', element: <RequirePermission code="cash.manage"><CashSessionsPage /></RequirePermission> },
      { path: 'cash-sessions/:id', element: <RequirePermission code="cash.open"><CashSessionPage /></RequirePermission> },
      { path: 'sales', element: <RequirePermission code="sale.read"><SalesPage /></RequirePermission> },
      { path: 'sales/:id', element: <RequirePermission code="sale.read"><SaleDetailPage /></RequirePermission> },
      { path: 'returns/customer', element: <RequirePermission code="return.read"><CustomerReturnsPage /></RequirePermission> },
      { path: 'returns/customer/new', element: <RequirePermission code="return.customer.create"><NewCustomerReturnPage /></RequirePermission> },
      { path: 'returns/customer/:id', element: <RequirePermission code="return.read"><CustomerReturnPage /></RequirePermission> },
      { path: 'customers', element: <RequirePermission code="customer.read"><CustomersPage /></RequirePermission> },
      { path: 'customers/new', element: <RequirePermission code="customer.manage"><NewCustomerPage /></RequirePermission> },
      { path: 'customers/:id', element: <RequirePermission code="customer.read"><CustomerPage /></RequirePermission> },
      { path: 'customer-categories', element: <RequirePermission code="customer.read"><CustomerCategoriesPage /></RequirePermission> },
      { path: 'catalog/references', element: <RequirePermission code="product.read"><ReferencesPage /></RequirePermission> },
      { path: 'reports', element: <ReportsPage /> },
      { path: 'reports/:key', element: <ReportPage /> },
      { path: 'admin/users', element: <RequirePermission code="user.read"><UsersPage /></RequirePermission> },
      { path: 'admin/users/new', element: <RequirePermission code="user.manage"><NewUserPage /></RequirePermission> },
      { path: 'admin/users/:id', element: <RequirePermission code="user.read"><UserDetailPage /></RequirePermission> },
      { path: 'admin/roles', element: <RequirePermission code="role.manage"><RolesPage /></RequirePermission> },
      { path: 'admin/settings', element: <RequirePermission code="settings.manage"><SettingsPage /></RequirePermission> },
      { path: 'admin/audit', element: <RequirePermission code="audit.read"><AuditPage /></RequirePermission> },
      { path: 'admin/backups', element: <RequirePermission code="backup.manage"><BackupsPage /></RequirePermission> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]

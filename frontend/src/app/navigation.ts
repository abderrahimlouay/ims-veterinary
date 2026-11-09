import type { NavIconName } from '../components/NavIcon'

/** The sections of the side navigation, in order; an entry without a group sits above them (frontend.md §3). */
export const NAV_GROUPS = ['sales', 'stock', 'purchasing', 'finance', 'admin'] as const
export type NavGroup = (typeof NAV_GROUPS)[number]

/** One side-navigation entry; `permission` hides it from users who lack it (frontend.md §3). */
export interface NavItem {
  to: string
  labelKey: string
  icon: NavIconName
  group?: NavGroup
  permission?: string
  /** Shown when the user holds at least one of these. */
  anyPermission?: readonly string[]
}

/** Entries are added with their pages, phase by phase (frontend.md §3.1), grouped by section. */
export const NAV_ITEMS: readonly NavItem[] = [
  { to: '/', labelKey: 'nav.home', icon: 'home' },
  { to: '/pos', labelKey: 'nav.pos', icon: 'pos', permission: 'sale.create' },
  { to: '/sales', labelKey: 'nav.sales', icon: 'document', group: 'sales', permission: 'sale.read' },
  { to: '/returns/customer', labelKey: 'nav.customerReturns', icon: 'returnIn', group: 'sales', permission: 'return.read' },
  { to: '/customers', labelKey: 'nav.customers', icon: 'customers', group: 'sales', permission: 'customer.read' },
  { to: '/customer-categories', labelKey: 'nav.customerCategories', icon: 'tag', group: 'sales', permission: 'customer.read' },
  { to: '/products', labelKey: 'nav.products', icon: 'box', group: 'stock', permission: 'product.read' },
  { to: '/lots', labelKey: 'nav.lots', icon: 'layers', group: 'stock', permission: 'lot.read' },
  { to: '/stock', labelKey: 'nav.stock', icon: 'warehouse', group: 'stock', permission: 'stock.read' },
  { to: '/inventory-counts', labelKey: 'nav.counts', icon: 'clipboardCheck', group: 'stock', permission: 'inventory.count' },
  { to: '/reorder', labelKey: 'nav.reorder', icon: 'refresh', group: 'stock', permission: 'reorder.read' },
  { to: '/catalog/references', labelKey: 'nav.references', icon: 'book', group: 'stock', permission: 'product.read' },
  { to: '/suppliers', labelKey: 'nav.suppliers', icon: 'truck', group: 'purchasing', permission: 'supplier.read' },
  { to: '/purchase-orders', labelKey: 'nav.purchaseOrders', icon: 'clipboardList', group: 'purchasing', permission: 'purchase.read' },
  { to: '/goods-receipts', labelKey: 'nav.goodsReceipts', icon: 'inboxIn', group: 'purchasing', permission: 'receipt.read' },
  { to: '/supplier-returns', labelKey: 'nav.supplierReturns', icon: 'inboxOut', group: 'purchasing', permission: 'return.read' },
  { to: '/supplier-invoices', labelKey: 'nav.supplierInvoices', icon: 'receipt', group: 'purchasing', permission: 'supplierinvoice.manage' },
  { to: '/cash-sessions', labelKey: 'nav.cashSessions', icon: 'wallet', group: 'finance', permission: 'cash.manage' },
  { to: '/reports', labelKey: 'nav.reports', icon: 'chart', group: 'finance', anyPermission: ['report.sales', 'report.margin', 'report.stock', 'report.finance', 'lot.recall'] },
  { to: '/admin/users', labelKey: 'nav.users', icon: 'user', group: 'admin', permission: 'user.read' },
  { to: '/admin/roles', labelKey: 'nav.roles', icon: 'shield', group: 'admin', permission: 'role.manage' },
  { to: '/admin/settings', labelKey: 'nav.settings', icon: 'sliders', group: 'admin', permission: 'settings.manage' },
  { to: '/admin/audit', labelKey: 'nav.audit', icon: 'search', group: 'admin', permission: 'audit.read' },
  { to: '/admin/backups', labelKey: 'nav.backups', icon: 'database', group: 'admin', permission: 'backup.manage' },
]

export function visibleNavItems(items: readonly NavItem[], permissions: readonly string[]): NavItem[] {
  return items.filter(
    (item) =>
      (item.permission === undefined || permissions.includes(item.permission)) &&
      (item.anyPermission === undefined || item.anyPermission.some((p) => permissions.includes(p))),
  )
}

/** The section holding the page at `pathname`, if any: it opens by itself. */
export function groupOf(items: readonly NavItem[], pathname: string): NavGroup | undefined {
  return items.find((item) => item.to !== '/' && (pathname === item.to || pathname.startsWith(`${item.to}/`)))?.group
}

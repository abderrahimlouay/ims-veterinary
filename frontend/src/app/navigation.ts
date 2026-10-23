/** One side-navigation entry; `permission` hides it from users who lack it (frontend.md §3). */
export interface NavItem {
  to: string
  labelKey: string
  permission?: string
}

/** Entries are added with their pages, phase by phase (frontend.md §3.1). */
export const NAV_ITEMS: readonly NavItem[] = [
  { to: '/', labelKey: 'nav.home' },
  { to: '/products', labelKey: 'nav.products', permission: 'product.read' },
  { to: '/lots', labelKey: 'nav.lots', permission: 'lot.read' },
  { to: '/stock', labelKey: 'nav.stock', permission: 'stock.read' },
  { to: '/catalog/references', labelKey: 'nav.references', permission: 'product.read' },
  { to: '/admin/users', labelKey: 'nav.users', permission: 'user.read' },
  { to: '/admin/roles', labelKey: 'nav.roles', permission: 'role.manage' },
  { to: '/admin/settings', labelKey: 'nav.settings', permission: 'settings.manage' },
  { to: '/admin/audit', labelKey: 'nav.audit', permission: 'audit.read' },
]

export function visibleNavItems(items: readonly NavItem[], permissions: readonly string[]): NavItem[] {
  return items.filter((item) => item.permission === undefined || permissions.includes(item.permission))
}

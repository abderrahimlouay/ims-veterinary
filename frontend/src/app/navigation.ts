/** One side-navigation entry; `permission` hides it from users who lack it (frontend.md §3). */
export interface NavItem {
  to: string
  labelKey: string
  permission?: string
}

/** Entries are added with their pages, phase by phase (frontend.md §3.1). */
export const NAV_ITEMS: readonly NavItem[] = [{ to: '/', labelKey: 'nav.home' }]

export function visibleNavItems(items: readonly NavItem[], permissions: readonly string[]): NavItem[] {
  return items.filter((item) => item.permission === undefined || permissions.includes(item.permission))
}

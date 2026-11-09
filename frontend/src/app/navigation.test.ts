import { describe, expect, it } from 'vitest'
import { groupOf, NAV_ITEMS, visibleNavItems, type NavItem } from './navigation'

describe('visibleNavItems', () => {
  const items: NavItem[] = [
    { to: '/', labelKey: 'nav.home', icon: 'home' },
    { to: '/admin/audit', labelKey: 'nav.audit', icon: 'home', permission: 'audit.read' },
  ]

  it('hides entries whose permission the user lacks (cosmetic, BR-SEC-002)', () => {
    expect(visibleNavItems(items, ['sale.read']).map((i) => i.to)).toEqual(['/'])
    expect(visibleNavItems(items, ['audit.read']).map((i) => i.to)).toEqual(['/', '/admin/audit'])
  })

  it('finds the section of a page, sub-pages included, and none for the home or the counter', () => {
    expect(groupOf(NAV_ITEMS, '/products/12')).toBe('stock')
    expect(groupOf(NAV_ITEMS, '/stock/adjustments/new')).toBe('stock')
    expect(groupOf(NAV_ITEMS, '/goods-receipts')).toBe('purchasing')
    expect(groupOf(NAV_ITEMS, '/admin/backups')).toBe('admin')
    expect(groupOf(NAV_ITEMS, '/')).toBeUndefined()
    expect(groupOf(NAV_ITEMS, '/pos')).toBeUndefined()
  })
})

import { describe, expect, it } from 'vitest'
import { visibleNavItems, type NavItem } from './navigation'

describe('visibleNavItems', () => {
  const items: NavItem[] = [
    { to: '/', labelKey: 'nav.home' },
    { to: '/admin/audit', labelKey: 'nav.audit', permission: 'audit.read' },
  ]

  it('hides entries whose permission the user lacks (cosmetic, BR-SEC-002)', () => {
    expect(visibleNavItems(items, ['sale.read']).map((i) => i.to)).toEqual(['/'])
    expect(visibleNavItems(items, ['audit.read']).map((i) => i.to)).toEqual(['/', '/admin/audit'])
  })
})

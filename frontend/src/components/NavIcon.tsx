import type { ReactNode } from 'react'

/**
 * Flat line icons of the side navigation, drawn here (no icon dependency): 24 × 24, one stroke in the text colour
 * (`currentColor`, so the theme's tokens apply, ADR-069), hidden from assistive technology since the link has its text.
 */
const PATHS = {
  home: (
    <>
      <path d="M3 11l9-7 9 7" />
      <path d="M5 10v10h14V10" />
      <path d="M10 20v-6h4v6" />
    </>
  ),
  pos: (
    <>
      <path d="M6 4h8v5H6z" />
      <path d="M10 9v4" />
      <path d="M3 13h18v7H3z" />
      <path d="M7 16.5h2M11 16.5h2M15 16.5h2" />
    </>
  ),
  document: (
    <>
      <path d="M6 3h9l4 4v14H6z" />
      <path d="M15 3v4h4" />
      <path d="M9 12h7M9 16h7" />
    </>
  ),
  returnIn: (
    <>
      <path d="M9 7l-5 5 5 5" />
      <path d="M4 12h11a5 5 0 0 1 5 5v2" />
    </>
  ),
  customers: (
    <>
      <circle cx="9" cy="8" r="3" />
      <path d="M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6" />
      <path d="M16 5a3 3 0 0 1 0 6" />
      <path d="M18 14.5c1.8.8 3 2.7 3 5" />
    </>
  ),
  tag: (
    <>
      <path d="M3 12V4h8l10 10-8 8z" />
      <circle cx="7.5" cy="7.5" r="1.5" />
    </>
  ),
  box: (
    <>
      <path d="M3 7l9-4 9 4v10l-9 4-9-4z" />
      <path d="M3 7l9 4 9-4" />
      <path d="M12 11v10" />
    </>
  ),
  layers: (
    <>
      <path d="M12 3l9 5-9 5-9-5z" />
      <path d="M3 13l9 5 9-5" />
    </>
  ),
  warehouse: (
    <>
      <path d="M3 21V9l9-5 9 5v12" />
      <path d="M7 21v-8h10v8" />
      <path d="M7 17h10" />
    </>
  ),
  clipboardCheck: (
    <>
      <path d="M9 3h6v3H9z" />
      <path d="M7 4.5H5V21h14V4.5h-2" />
      <path d="M9 14l2 2 4-4" />
    </>
  ),
  refresh: (
    <>
      <path d="M20 12a8 8 0 1 1-2.3-5.7" />
      <path d="M20 4v5h-5" />
    </>
  ),
  book: (
    <>
      <path d="M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3z" />
      <path d="M5 17a3 3 0 0 1 3-3h11" />
    </>
  ),
  truck: (
    <>
      <path d="M2 6h11v10H2z" />
      <path d="M13 10h4l4 4v2h-8" />
      <circle cx="6" cy="18" r="2" />
      <circle cx="17" cy="18" r="2" />
    </>
  ),
  clipboardList: (
    <>
      <path d="M9 3h6v3H9z" />
      <path d="M7 4.5H5V21h14V4.5h-2" />
      <path d="M9 11h6M9 15h6" />
    </>
  ),
  inboxIn: (
    <>
      <path d="M4 13h4l2 3h4l2-3h4" />
      <path d="M4 13v7h16v-7" />
      <path d="M12 3v9M8 8l4 4 4-4" />
    </>
  ),
  inboxOut: (
    <>
      <path d="M4 13h4l2 3h4l2-3h4" />
      <path d="M4 13v7h16v-7" />
      <path d="M12 12V3M8 7l4-4 4 4" />
    </>
  ),
  receipt: (
    <>
      <path d="M6 3h12v18l-3-2-3 2-3-2-3 2z" />
      <path d="M9 8h6M9 12h6" />
    </>
  ),
  wallet: (
    <>
      <path d="M3 7h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H3z" />
      <path d="M3 7V5a2 2 0 0 1 2-2h11" />
      <path d="M16 13.5h2" />
    </>
  ),
  chart: (
    <>
      <path d="M4 20v-9M10 20V5M16 20v-6" />
      <path d="M3 20h18" />
    </>
  ),
  user: (
    <>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21c0-4.4 3.6-8 8-8s8 3.6 8 8" />
    </>
  ),
  shield: (
    <>
      <path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z" />
      <path d="M9 12l2 2 4-4" />
    </>
  ),
  sliders: (
    <>
      <path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0" />
      <circle cx="16" cy="6" r="2" />
      <circle cx="10" cy="12" r="2" />
      <circle cx="18" cy="18" r="2" />
    </>
  ),
  search: (
    <>
      <circle cx="11" cy="11" r="6" />
      <path d="M20 20l-4.5-4.5" />
    </>
  ),
  database: (
    <>
      <ellipse cx="12" cy="5" rx="8" ry="3" />
      <path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5" />
      <path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3" />
    </>
  ),
  account: (
    <>
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="10" r="3" />
      <path d="M6.5 18.5c1.2-2 3.2-3 5.5-3s4.3 1 5.5 3" />
    </>
  ),
  chevron: <path d="M9 6l6 6-6 6" />,
} satisfies Record<string, ReactNode>

export type NavIconName = keyof typeof PATHS

export function NavIcon({ name, className = 'size-[18px] shrink-0' }: { name: NavIconName; className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      className={className}
    >
      {PATHS[name]}
    </svg>
  )
}

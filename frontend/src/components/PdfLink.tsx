import type { ReactNode } from 'react'

/** A server-rendered PDF opened in a new tab, where the browser prints it (frontend.md §13, ADR-139). */
export function PdfLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <a href={href} target="_blank" rel="noopener" className="h-10 px-4 inline-flex items-center rounded-ctl border border-control text-sm font-medium self-start">
      {children}
    </a>
  )
}

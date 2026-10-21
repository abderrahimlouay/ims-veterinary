import { useTranslation } from 'react-i18next'
import { Button } from './Button'

export interface PageMetadata {
  number: number
  size: number
  totalElements: number
  totalPages: number
}

/** Previous/next for the paging envelope of api.md §1. */
export function Pager({ page, onPage }: { page: PageMetadata; onPage: (page: number) => void }) {
  const { t } = useTranslation()
  return (
    <nav aria-label={t('pager.label')} className="flex items-center gap-3 text-sm">
      <Button variant="secondary" disabled={page.number === 0} onClick={() => onPage(page.number - 1)}>
        {t('pager.previous')}
      </Button>
      <span>{t('pager.position', { page: page.number + 1, pages: Math.max(page.totalPages, 1), total: page.totalElements })}</span>
      <Button
        variant="secondary"
        disabled={page.number + 1 >= page.totalPages}
        onClick={() => onPage(page.number + 1)}
      >
        {t('pager.next')}
      </Button>
    </nav>
  )
}

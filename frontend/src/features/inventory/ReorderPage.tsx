import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { Pager } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { problemMessage } from '../../lib/errors'
import { formatDateTime, formatQuantity } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { PROPOSAL_STATUSES, useDismissProposal, useGenerateProposals, useProposals } from './reorderApi'

/**
 * `/reorder` (frontend.md §4.3, W10): proposals by status (pending by default); "Generate now" runs the daily
 * generation at once and lists the products whose thresholds are incomplete (no max stock, ADR-077) or inconsistent
 * (max stock at or below the reorder point), apart (ADR-078); "Dismiss" holds
 * the product back until its stock recovers (ADR-079). Conversion to a purchase order arrives in Phase 5.
 */
export function ReorderPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? 'pending'
  const page = Number(params.get('page') ?? '0')
  const proposals = useProposals(status === 'all' ? '' : status, page)
  const generate = useGenerateProposals()
  const dismiss = useDismissProposal()
  const manage = hasPermission(current, 'reorder.manage')

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">{t('reorder.title')}</h1>
        {manage && (
          <Button className="ms-auto" disabled={generate.isPending} onClick={() => generate.mutate()}>
            {t('reorder.generate')}
          </Button>
        )}
      </div>
      {generate.data && (
        <Alert tone="ok">
          {t('reorder.generated', { created: generate.data.created, updated: generate.data.updated })}
          <ProductLinks label={t('reorder.incomplete')} products={generate.data.thresholdsIncomplete} />
          <ProductLinks label={t('reorder.inconsistent')} products={generate.data.thresholdsInconsistent} />
        </Alert>
      )}
      {generate.error instanceof ApiError && <Alert tone="danger">{problemMessage(generate.error.problem, t)}</Alert>}
      {dismiss.error instanceof ApiError && <Alert tone="danger">{problemMessage(dismiss.error.problem, t)}</Alert>}
      <div className="max-w-xs">
        <SelectField
          label={t('reorder.statusFilter')}
          value={status}
          onChange={(e) => setParams(e.target.value === 'pending' ? {} : { status: e.target.value }, { replace: true })}
          options={[...PROPOSAL_STATUSES.map((s) => ({ value: s, label: t(`reorder.status.${s}`) })), { value: 'all', label: t('admin.all') }]}
        />
      </div>
      {proposals.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : proposals.error instanceof ApiError ? (
        <Alert tone="danger">{problemMessage(proposals.error.problem, t)}</Alert>
      ) : !proposals.data || proposals.data.content.length === 0 ? (
        <p className="text-mute">{t('reorder.empty')}</p>
      ) : (
        <>
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr className="border-b border-line">
                <th className="text-start py-2">{t('catalog.products.name')}</th>
                <th className="text-end">{t('reorder.current')}</th>
                <th className="text-end">{t('reorder.suggested')}</th>
                <th className="text-end">{t('catalog.products.reorderPoint')}</th>
                <th className="text-end">{t('catalog.products.maxStock')}</th>
                <th className="text-start ps-4">{t('reorder.updatedAt')}</th>
                <th className="text-start">{t('reorder.statusFilter')}</th>
                {manage && <th className="text-end">{t('reorder.actions')}</th>}
              </tr>
            </thead>
            <tbody>
              {proposals.data.content.map((p) => (
                <tr key={p.id} className="border-b border-line">
                  <td className="py-2">
                    <Link to={`/products/${p.product.id}`} className="text-brand underline underline-offset-4">
                      {p.product.commercialName}
                    </Link>{' '}
                    <code className="text-mute">{p.product.sku}</code>
                  </td>
                  <td className="text-end">{formatQuantity(p.currentQty)}</td>
                  <td className="text-end font-semibold">{`${formatQuantity(p.suggestedQty)} ${p.product.baseUnit}`}</td>
                  <td className="text-end">{formatQuantity(p.reorderPoint)}</td>
                  <td className="text-end">{p.maxStock !== null ? formatQuantity(p.maxStock) : '—'}</td>
                  <td className="ps-4">{formatDateTime(p.updatedAt)}</td>
                  <td>
                    {p.status === 'dismissed' && p.dismissedBy
                      ? t('reorder.dismissedBy', { by: p.dismissedBy.fullName })
                      : t(`reorder.status.${p.status}`)}
                  </td>
                  {manage && (
                    <td className="text-end">
                      {p.status === 'pending' && (
                        <Button
                          variant="secondary"
                          disabled={dismiss.isPending}
                          aria-label={t('reorder.dismissFor', { name: p.product.commercialName })}
                          onClick={() => dismiss.mutate(p.id)}
                        >
                          {t('reorder.dismiss')}
                        </Button>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={proposals.data.page} onPage={(next) => setParams({ ...(status === 'pending' ? {} : { status }), page: String(next) }, { replace: true })} />
        </>
      )}
    </section>
  )
}

function ProductLinks({ label, products }: { label: string; products: { id: number; commercialName: string }[] }) {
  if (products.length === 0) {
    return null
  }
  return (
    <span className="block mt-1">
      {label}{' '}
      {products.map((p, i) => (
        <span key={p.id}>
          {i > 0 && ', '}
          <Link to={`/products/${p.id}`} className="underline">
            {p.commercialName}
          </Link>
        </span>
      ))}
    </span>
  )
}

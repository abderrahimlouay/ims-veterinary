import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { problemMessage } from '../../lib/errors'
import { useReplaceTargetSpecies, useReplaceWithdrawalPeriods, type ProductDetail } from './productApi'
import { referenceLabel, useReferenceList } from './referenceApi'

const DAY_FIELDS = ['meatDays', 'milkDays', 'eggsDays'] as const
type DayField = (typeof DAY_FIELDS)[number]

interface PeriodRow {
  key: number
  speciesId: string
  meatDays: string
  milkDays: string
  eggsDays: string
}

/** An empty field is "not specified" (`null`), never 0 (BR-PROD-007). */
function toDays(value: string): number | null {
  return value.trim() === '' ? null : Number(value)
}

/**
 * Target species (ADR-047) and withdrawal periods (FR-PROD-005) of a product; both feed the species filter of
 * the search. Each is saved as a whole set with `product.update`.
 */
export function SpeciesPanel({ product, editable }: { product: ProductDetail; editable: boolean }) {
  const { t } = useTranslation()
  const species = useReferenceList('animal-species')
  const saveTargets = useReplaceTargetSpecies(product.id)
  const savePeriods = useReplaceWithdrawalPeriods(product.id)
  const [targets, setTargets] = useState<Set<number>>(() => new Set(product.targetSpecies.map((s) => s.id)))
  const [rows, setRows] = useState<PeriodRow[]>(() =>
    product.withdrawalPeriods.map((w, key) => ({
      key,
      speciesId: String(w.species.id),
      meatDays: w.meatDays?.toString() ?? '',
      milkDays: w.milkDays?.toString() ?? '',
      eggsDays: w.eggsDays?.toString() ?? '',
    })),
  )
  const [nextKey, setNextKey] = useState(rows.length)
  const label = (s: { id: number; name: string; code: string | null; country: string | null }) =>
    referenceLabel(s, 'species', t)
  const periodsError = savePeriods.error instanceof ApiError ? savePeriods.error.problem : undefined

  if (!editable) {
    return (
      <div className="flex flex-col gap-4">
        <p>
          <span className="text-mute">{t('catalog.species.targets')} : </span>
          {product.targetSpecies.length === 0 ? '—' : product.targetSpecies.map(label).join(', ')}
        </p>
        <PeriodsTable product={product} label={label} />
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-6 max-w-2xl">
      <fieldset className="flex flex-col gap-2">
        <legend className="font-semibold mb-1">{t('catalog.species.targets')}</legend>
        {saveTargets.isSuccess && <Alert tone="ok">{t('admin.saved')}</Alert>}
        {saveTargets.error instanceof ApiError && <Alert tone="danger">{problemMessage(saveTargets.error.problem, t)}</Alert>}
        <div className="flex flex-wrap gap-4">
          {(species.data ?? []).map((s) => (
            <label key={s.id} className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={targets.has(s.id)}
                onChange={() =>
                  setTargets((current) => {
                    const next = new Set(current)
                    if (next.has(s.id)) {
                      next.delete(s.id)
                    } else {
                      next.add(s.id)
                    }
                    return next
                  })
                }
              />
              {label(s)}
            </label>
          ))}
        </div>
        <div>
          <Button disabled={saveTargets.isPending} onClick={() => saveTargets.mutate([...targets])}>
            {t('catalog.species.saveTargets')}
          </Button>
        </div>
      </fieldset>

      <fieldset className="flex flex-col gap-2">
        <legend className="font-semibold mb-1">{t('catalog.species.periods')}</legend>
        <p className="text-sm text-mute">{t('catalog.species.notSpecifiedHint')}</p>
        {savePeriods.isSuccess && <Alert tone="ok">{t('admin.saved')}</Alert>}
        {periodsError && (
          <Alert tone="danger">
            {periodsError.errors[0]
              ? t(`catalog.species.errors.${periodsError.errors[0].code}`, { defaultValue: problemMessage(periodsError, t) })
              : problemMessage(periodsError, t)}
          </Alert>
        )}
        {rows.map((row, index) => (
          <div key={row.key} className="flex items-end gap-2">
            <label className="flex flex-col gap-1 flex-1 text-sm font-medium">
              {t('catalog.species.species', { n: index + 1 })}
              <select
                value={row.speciesId}
                onChange={(e) => setRows((rs) => rs.map((r) => (r.key === row.key ? { ...r, speciesId: e.target.value } : r)))}
                className="h-10 px-2 rounded-ctl bg-surface border border-line text-sm font-normal"
              >
                <option value="">—</option>
                {(species.data ?? []).map((s) => (
                  <option key={s.id} value={s.id}>
                    {label(s)}
                  </option>
                ))}
              </select>
            </label>
            {DAY_FIELDS.map((field: DayField) => (
              <label key={field} className="flex flex-col gap-1 w-24 text-sm font-medium">
                {t(`catalog.species.${field}`, { n: index + 1 })}
                <input
                  type="number"
                  min={0}
                  value={row[field]}
                  onChange={(e) => setRows((rs) => rs.map((r) => (r.key === row.key ? { ...r, [field]: e.target.value } : r)))}
                  className="h-10 px-2 rounded-ctl bg-surface border border-line text-sm font-normal"
                />
              </label>
            ))}
            <Button variant="secondary" onClick={() => setRows((rs) => rs.filter((r) => r.key !== row.key))}>
              {t('catalog.ingredients.remove', { n: index + 1 })}
            </Button>
          </div>
        ))}
        <div className="flex gap-3">
          <Button
            variant="secondary"
            onClick={() => {
              setRows((rs) => [...rs, { key: nextKey, speciesId: '', meatDays: '', milkDays: '', eggsDays: '' }])
              setNextKey((k) => k + 1)
            }}
          >
            {t('catalog.species.addPeriod')}
          </Button>
          <Button
            disabled={savePeriods.isPending}
            onClick={() =>
              savePeriods.mutate(
                rows
                  .filter((r) => r.speciesId !== '')
                  .map((r) => ({
                    speciesId: Number(r.speciesId),
                    meatDays: toDays(r.meatDays),
                    milkDays: toDays(r.milkDays),
                    eggsDays: toDays(r.eggsDays),
                  })),
              )
            }
          >
            {t('catalog.species.savePeriods')}
          </Button>
        </div>
      </fieldset>
    </div>
  )
}

function PeriodsTable({
  product,
  label,
}: {
  product: ProductDetail
  label: (s: { id: number; name: string; code: string | null; country: string | null }) => string
}) {
  const { t } = useTranslation()
  if (product.withdrawalPeriods.length === 0) {
    return <p className="text-mute">{t('catalog.species.noPeriods')}</p>
  }
  const days = (value: number | null) => (value === null ? t('catalog.species.notSpecified') : String(value))
  return (
    <table className="text-sm border-collapse">
      <thead>
        <tr className="border-b border-line">
          <th className="text-start pe-6 py-1">{t('catalog.species.speciesHeader')}</th>
          {DAY_FIELDS.map((f) => (
            <th key={f} className="text-start pe-6">
              {t(`catalog.species.${f}Header`)}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {product.withdrawalPeriods.map((w) => (
          <tr key={w.species.id} className="border-b border-line">
            <td className="py-1 pe-6">{label(w.species)}</td>
            <td className="pe-6">{days(w.meatDays)}</td>
            <td className="pe-6">{days(w.milkDays)}</td>
            <td className="pe-6">{days(w.eggsDays)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

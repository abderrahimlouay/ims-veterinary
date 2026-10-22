import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import type { Problem } from '../../api/problem'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import {
  BARCODE_TYPES,
  useAddBarcode,
  useBarcodes,
  useRemoveBarcode,
  useUnits,
  type BarcodeType,
  type ProductDetail,
} from './productApi'

/** Field errors of a failed add: `errors[]` on `code`/`unitId`, and BARCODE_DUPLICATE on the code (BR-PROD-002). */
function fieldErrors(problem: Problem): { code?: string; unitId?: string } {
  if (problem.code === 'BARCODE_DUPLICATE') {
    return { code: 'errors.BARCODE_DUPLICATE' }
  }
  const byField = (field: string) => problem.errors.find((e) => e.field === field)?.code
  const code = byField('code')
  const unitId = byField('unitId')
  return {
    code: code === undefined ? undefined : `fieldErrors.${code}`,
    unitId: unitId === undefined ? undefined : `fieldErrors.${unitId}`,
  }
}

/**
 * Barcodes of a product (FR-PROD-003, frontend.md §4.1): code, type and unit (none = base unit, ADR-057), with
 * a format hint per type (ADR-067). Barcodes are added or removed, never edited.
 */
export function BarcodesPanel({ product, editable }: { product: ProductDetail; editable: boolean }) {
  const { t } = useTranslation()
  const barcodes = useBarcodes(product.id)
  const remove = useRemoveBarcode(product.id)

  if (barcodes.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (barcodes.error instanceof ApiError) {
    return <Alert tone="danger">{problemMessage(barcodes.error.problem, t)}</Alert>
  }
  const list = barcodes.data ?? []
  return (
    <div className="flex flex-col gap-6 max-w-3xl">
      {remove.error instanceof ApiError && <Alert tone="danger">{problemMessage(remove.error.problem, t)}</Alert>}
      {list.length === 0 ? (
        <p className="text-mute">{t('catalog.barcodes.empty')}</p>
      ) : (
        <table className="text-sm border-collapse">
          <thead>
            <tr className="border-b border-line">
              <th className="text-start py-2 pe-6">{t('catalog.barcodes.code')}</th>
              <th className="text-start pe-6">{t('catalog.barcodes.type')}</th>
              <th className="text-start pe-6">{t('catalog.barcodes.unit')}</th>
              {editable && <th />}
            </tr>
          </thead>
          <tbody>
            {list.map((b) => (
              <tr key={b.id} className="border-b border-line">
                <td className="py-2 pe-6">
                  <code>{b.code}</code>
                </td>
                <td className="pe-6">{t(`catalog.barcodes.types.${b.codeType}`)}</td>
                <td className="pe-6">{b.unit ? b.unit.name : t('catalog.barcodes.baseUnit', { baseUnit: product.baseUnit })}</td>
                {editable && (
                  <td>
                    <Button variant="secondary" disabled={remove.isPending} onClick={() => remove.mutate(b.id)}>
                      {t('catalog.barcodes.remove', { code: b.code })}
                    </Button>
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {editable && <BarcodeForm product={product} />}
    </div>
  )
}

function BarcodeForm({ product }: { product: ProductDetail }) {
  const { t } = useTranslation()
  const units = useUnits(product.id)
  const add = useAddBarcode(product.id)
  const [code, setCode] = useState('')
  const [codeType, setCodeType] = useState<BarcodeType>('EAN13')
  const [unitId, setUnitId] = useState('')
  const [saved, setSaved] = useState(false)
  const problem = add.error instanceof ApiError ? add.error.problem : undefined
  const errors = problem ? fieldErrors(problem) : {}
  const placed = errors.code !== undefined || errors.unitId !== undefined

  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    setSaved(false)
    add.mutate(
      { code, codeType, unitId: unitId === '' ? null : Number(unitId) },
      {
        onSuccess: () => {
          setSaved(true)
          setCode('')
          setUnitId('')
        },
      },
    )
  }

  return (
    <form onSubmit={onSubmit} noValidate className="flex flex-col gap-3">
      <fieldset className="flex flex-col gap-3">
        <legend className="font-semibold mb-1">{t('catalog.barcodes.addTitle')}</legend>
        {saved && <Alert tone="ok">{t('admin.saved')}</Alert>}
        {problem && !placed && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
        <div className="grid grid-cols-3 gap-4">
          <TextField
            label={t('catalog.barcodes.code')}
            value={code}
            error={errors.code === undefined ? undefined : t(errors.code)}
            onChange={(e) => setCode(e.target.value)}
          />
          <SelectField
            label={t('catalog.barcodes.type')}
            value={codeType}
            onChange={(e) => setCodeType(e.target.value as BarcodeType)}
            options={BARCODE_TYPES.map((type) => ({ value: type, label: t(`catalog.barcodes.types.${type}`) }))}
          />
          <SelectField
            label={t('catalog.barcodes.unit')}
            value={unitId}
            error={errors.unitId === undefined ? undefined : t(errors.unitId)}
            onChange={(e) => setUnitId(e.target.value)}
            options={[
              { value: '', label: t('catalog.barcodes.baseUnit', { baseUnit: product.baseUnit }) },
              ...(units.data ?? []).map((u) => ({ value: String(u.id), label: u.name })),
            ]}
          />
        </div>
        <p className="text-sm text-mute">{t(`catalog.barcodes.hints.${codeType}`)}</p>
      </fieldset>
      <div>
        <Button type="submit" disabled={add.isPending}>
          {t('catalog.barcodes.add')}
        </Button>
      </div>
    </form>
  )
}

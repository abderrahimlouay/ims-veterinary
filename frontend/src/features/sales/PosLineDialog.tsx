import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { formatDate, formatQuantity } from '../../lib/format'
import { useUnits } from '../catalog'
import { useProductLots } from '../inventory/lotApi'
import type { LineWrite, SaleLine } from './saleApi'

/**
 * Changes a cart line: quantity and unit always; a manual discount with `sale.discount` (BR-SALE-007); another lot with
 * `sale.override_fefo` and a mandatory reason, only sellable lots being offered (ADR-120). The server decides anyway.
 */
export function PosLineDialog({
  line,
  canDiscount,
  canOverride,
  isQuote,
  onSave,
  onClose,
}: {
  line: SaleLine
  canDiscount: boolean
  canOverride: boolean
  isQuote: boolean
  onSave: (write: LineWrite) => Promise<unknown>
  onClose: () => void
}) {
  const { t } = useTranslation()
  const units = useUnits(line.productId)
  const showLots = canOverride && !isQuote
  const lots = useProductLots(line.productId, showLots)
  const [quantity, setQuantity] = useState(line.quantity)
  const [unitId, setUnitId] = useState(line.unitId === null ? '' : String(line.unitId))
  const [discount, setDiscount] = useState('')
  const [lotId, setLotId] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [saving, setSaving] = useState(false)

  const sellable = (lots.data ?? []).filter((l) => l.status === 'active' && Number(l.quantityOnHand) > 0)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setSaving(true)
    setError(null)
    try {
      await onSave({
        productId: line.productId,
        unitId: unitId === '' ? null : Number(unitId),
        quantity: quantity.trim(),
        targetSpeciesId: line.targetSpeciesId,
        lotId: lotId === '' ? null : Number(lotId),
        fefoOverrideReason: lotId === '' ? null : reason.trim(),
        manualDiscountPct: discount.trim() === '' ? null : discount.trim(),
      })
      onClose()
    } catch (failure) {
      setError(failure)
    } finally {
      setSaving(false)
    }
  }
  return (
    <Dialog title={t('pos.line.title', { name: line.productName })} onClose={onClose}>
      <form onSubmit={submit} className="flex flex-col gap-3">
        {error instanceof ApiError && <Alert tone="danger">{problemMessage(error.problem, t)}</Alert>}
        <TextField label={t('pos.quantity')} inputMode="decimal" required value={quantity} onChange={(e) => setQuantity(e.target.value)} />
        <SelectField
          label={t('pos.line.unit')}
          value={unitId}
          onChange={(e) => setUnitId(e.target.value)}
          options={[
            { value: '', label: t('pos.line.baseUnit') },
            ...(units.data ?? []).filter((u) => u.sellable).map((u) => ({ value: String(u.id), label: u.name })),
          ]}
        />
        {canDiscount && (
          <TextField label={t('pos.line.discount')} inputMode="decimal" hint={t('pos.line.discountHint', { current: line.discountPct })} value={discount} onChange={(e) => setDiscount(e.target.value)} />
        )}
        {showLots && (
          <>
            <SelectField
              label={t('pos.line.lot')}
              value={lotId}
              onChange={(e) => setLotId(e.target.value)}
              options={[
                { value: '', label: t('pos.line.fefo') },
                ...sellable.map((l) => ({ value: String(l.id), label: `${l.lotNumber} · ${formatDate(l.expiryDate)} · ${formatQuantity(l.quantityOnHand)}` })),
              ]}
            />
            {lotId !== '' && <TextField label={t('pos.line.reason')} required maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />}
          </>
        )}
        <Button type="submit" disabled={saving}>
          {t('pos.line.save')}
        </Button>
      </form>
    </Dialog>
  )
}

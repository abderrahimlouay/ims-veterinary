import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { TextField } from '../../components/TextField'
import { problemMessage } from '../../lib/errors'
import { hasPermission, useCurrentUser } from '../auth'
import {
  REFERENCE_LISTS,
  referenceLabel,
  useDeleteReference,
  useReferenceList,
  useSaveReference,
  type ReferenceItem,
} from './referenceApi'

type ListSpec = (typeof REFERENCE_LISTS)[number]

/**
 * `/catalog/references` (FR-PROD-008): one tab per list; reading needs `product.read`, changes
 * `reference.manage`. Seeded entries are protected (ADR-054); used entries cannot be deleted (ADR-053).
 */
export function ReferencesPage() {
  const { t } = useTranslation()
  const [active, setActive] = useState<ListSpec>(REFERENCE_LISTS[0])
  return (
    <section className="flex flex-col gap-4 max-w-3xl">
      <h1 className="text-2xl font-semibold">{t('catalog.references.title')}</h1>
      <div role="tablist" aria-label={t('catalog.references.title')} className="flex gap-1 border-b border-line">
        {REFERENCE_LISTS.map((list) => (
          <button
            key={list.path}
            type="button"
            role="tab"
            aria-selected={list.path === active.path}
            onClick={() => setActive(list)}
            className={`px-3 py-2 text-sm font-medium border-b-2 ${list.path === active.path ? 'border-brand text-brand' : 'border-transparent text-ink'}`}
          >
            {t(`catalog.references.lists.${list.i18nKey}`)}
          </button>
        ))}
      </div>
      <div role="tabpanel">
        <ReferenceListPanel key={active.path} list={active} />
      </div>
    </section>
  )
}

function ReferenceListPanel({ list }: { list: ListSpec }) {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const canManage = hasPermission(current, 'reference.manage')
  const items = useReferenceList(list.path)
  const remove = useDeleteReference(list.path)
  const [editing, setEditing] = useState<ReferenceItem>()

  if (items.isPending) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }
  if (items.error instanceof ApiError) {
    return <Alert tone="danger">{problemMessage(items.error.problem, t)}</Alert>
  }
  return (
    <div className="flex flex-col gap-4">
      {remove.error instanceof ApiError && <Alert tone="danger">{problemMessage(remove.error.problem, t)}</Alert>}
      {items.data && items.data.length === 0 ? (
        <p className="text-mute">{t('catalog.references.empty')}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-line">
          {items.data?.map((item) => (
            <li key={item.id} className="flex items-center gap-3 py-2">
              <span className="flex-1">
                {referenceLabel(item, list.i18nKey, t)}
                {item.country && <span className="text-mute"> — {item.country}</span>}
              </span>
              {item.code !== null ? (
                <span className="text-xs text-mute">{t('catalog.references.seeded')}</span>
              ) : (
                canManage && (
                  <>
                    <Button variant="secondary" onClick={() => setEditing(item)}>
                      {t('catalog.references.rename')}
                    </Button>
                    <Button
                      variant="secondary"
                      disabled={remove.isPending}
                      aria-label={`${t('catalog.references.delete')} ${item.name}`}
                      onClick={() => remove.mutate(item.id)}
                    >
                      {t('catalog.references.delete')}
                    </Button>
                  </>
                )
              )}
            </li>
          ))}
        </ul>
      )}
      {canManage && (
        <ReferenceForm
          key={editing?.id ?? 'new'}
          list={list}
          editing={editing}
          onDone={() => setEditing(undefined)}
        />
      )}
    </div>
  )
}

function ReferenceForm({ list, editing, onDone }: { list: ListSpec; editing?: ReferenceItem; onDone: () => void }) {
  const { t } = useTranslation()
  const save = useSaveReference(list.path)
  const [name, setName] = useState(editing?.name ?? '')
  const [country, setCountry] = useState(editing?.country ?? '')
  const problem = save.error instanceof ApiError ? save.error.problem : undefined
  const nameError =
    problem?.code === 'DUPLICATE_RESOURCE'
      ? t('fieldErrors.DUPLICATE')
      : problem?.errors.find((e) => e.field === 'name')
        ? t('fieldErrors.INVALID_FORMAT')
        : undefined

  return (
    <form
      noValidate
      onSubmit={(e) => {
        e.preventDefault()
        save.mutate(
          { id: editing?.id, name, country: list.hasCountry ? country : null },
          {
            onSuccess: () => {
              setName('')
              setCountry('')
              onDone()
            },
          },
        )
      }}
      className="flex items-end gap-3 p-3 border border-line rounded-ctl bg-surface"
    >
      <div className="flex-1">
        <TextField
          label={editing ? t('catalog.references.newName') : t('catalog.references.addLabel')}
          value={name}
          onChange={(e) => setName(e.target.value)}
          error={nameError}
        />
      </div>
      {list.hasCountry && (
        <div className="w-40">
          <TextField label={t('catalog.references.country')} value={country} onChange={(e) => setCountry(e.target.value)} />
        </div>
      )}
      <Button type="submit" disabled={save.isPending || name.trim() === ''}>
        {editing ? t('admin.save') : t('catalog.references.add')}
      </Button>
      {editing && (
        <Button variant="secondary" onClick={onDone}>
          {t('catalog.references.cancel')}
        </Button>
      )}
      {problem && !nameError && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
    </form>
  )
}

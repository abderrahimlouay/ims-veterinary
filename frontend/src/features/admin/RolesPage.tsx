import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../api/client'
import { Alert } from '../../components/Alert'
import { Button } from '../../components/Button'
import { problemMessage } from '../../lib/errors'
import { usePermissions, useReplaceRolePermissions, useRoles, type RoleDetail } from './api'

type Grid = Record<number, ReadonlySet<string>>

interface RoleChange {
  role: RoleDetail
  added: string[]
  removed: string[]
  codes: string[]
}

function changesOf(roles: RoleDetail[], grid: Grid): RoleChange[] {
  return roles.flatMap((role) => {
    const before = new Set(role.permissions)
    const after = grid[role.id] ?? before
    const added = [...after].filter((c) => !before.has(c)).sort()
    const removed = [...before].filter((c) => !after.has(c)).sort()
    return added.length + removed.length === 0 ? [] : [{ role, added, removed, codes: [...after].sort() }]
  })
}

/**
 * `/admin/roles` (frontend.md §4): permission × role checkbox grid; changes are reviewed in a summary,
 * then saved role by role (each save is audited and checked against lock-out, ADR-042).
 */
export function RolesPage() {
  const { t } = useTranslation()
  const roles = useRoles()
  const permissions = usePermissions()
  const replace = useReplaceRolePermissions()
  const [grid, setGrid] = useState<Grid>({})
  const [reviewing, setReviewing] = useState(false)
  const [problem, setProblem] = useState<ApiError['problem']>()
  const [saved, setSaved] = useState(false)
  const changes = useMemo(() => changesOf(roles.data ?? [], grid), [roles.data, grid])

  if (!roles.data || !permissions.data) {
    return (
      <p role="status" className="text-mute">
        {t('app.loading')}
      </p>
    )
  }

  const granted = (role: RoleDetail, code: string) => (grid[role.id] ?? new Set(role.permissions)).has(code)
  const toggle = (role: RoleDetail, code: string) => {
    setSaved(false)
    setGrid((g) => {
      const next = new Set(g[role.id] ?? role.permissions)
      if (next.has(code)) {
        next.delete(code)
      } else {
        next.add(code)
      }
      return { ...g, [role.id]: next }
    })
  }
  const save = async () => {
    setProblem(undefined)
    try {
      for (const change of changes) {
        await replace.mutateAsync({ roleId: change.role.id, codes: change.codes })
      }
      setGrid({})
      setReviewing(false)
      setSaved(true)
    } catch (error) {
      setProblem(error instanceof ApiError ? error.problem : undefined)
    }
  }

  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-2xl font-semibold">{t('admin.roles.title')}</h1>
      {saved && <Alert tone="ok">{t('admin.saved')}</Alert>}
      {problem && <Alert tone="danger">{problemMessage(problem, t)}</Alert>}
      {reviewing ? (
        <div className="flex flex-col gap-3">
          <h2 className="text-lg font-semibold">{t('admin.roles.review')}</h2>
          <ul className="flex flex-col gap-2 text-sm">
            {changes.map((c) => (
              <li key={c.role.id}>
                <span className="font-medium">{c.role.name}</span>
                {c.added.length > 0 && <span className="text-ok"> + {c.added.join(', ')}</span>}
                {c.removed.length > 0 && <span className="text-danger"> − {c.removed.join(', ')}</span>}
              </li>
            ))}
          </ul>
          <div className="flex gap-3">
            <Button disabled={replace.isPending} onClick={() => void save()}>
              {t('admin.roles.confirm')}
            </Button>
            <Button variant="secondary" onClick={() => setReviewing(false)}>
              {t('admin.roles.back')}
            </Button>
          </div>
        </div>
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="text-sm border-collapse">
              <thead>
                <tr className="border-b border-line">
                  <th className="text-start py-2 pe-4">{t('admin.roles.permission')}</th>
                  {roles.data.map((r) => (
                    <th key={r.id} className="px-3">
                      {r.name}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {permissions.data.map((p) => (
                  <tr key={p.code} className="border-b border-line">
                    <th scope="row" className="text-start font-normal py-1 pe-4">
                      <code>{p.code}</code> <span className="text-mute">{p.description}</span>
                    </th>
                    {roles.data.map((r) => (
                      <td key={r.id} className="text-center">
                        <input
                          type="checkbox"
                          aria-label={`${r.name} ${p.code}`}
                          checked={granted(r, p.code)}
                          onChange={() => toggle(r, p.code)}
                        />
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div>
            <Button disabled={changes.length === 0} onClick={() => setReviewing(true)}>
              {t('admin.roles.reviewButton', { count: changes.length })}
            </Button>
          </div>
        </>
      )}
    </section>
  )
}

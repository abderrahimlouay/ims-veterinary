import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { Pager } from '../../components/Pager'
import { SelectField } from '../../components/SelectField'
import { TextField } from '../../components/TextField'
import { formatDateTime } from '../../lib/format'
import { hasPermission, useCurrentUser } from '../auth'
import { useRoles, useUsers, type UserFilter } from './api'

/** `/admin/users` (frontend.md §4): search, role and status filters; creation needs `user.manage`. */
export function UsersPage() {
  const { t } = useTranslation()
  const { data: current } = useCurrentUser()
  const roles = useRoles()
  const [filter, setFilter] = useState<UserFilter>({ q: '', roleId: '', active: '', page: 0 })
  const users = useUsers(filter)
  const update = (changes: Partial<UserFilter>) => setFilter((f) => ({ ...f, page: 0, ...changes }))

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center gap-4">
        <h1 className="text-2xl font-semibold">{t('admin.users.title')}</h1>
        {hasPermission(current, 'user.manage') && (
          <Link
            to="/admin/users/new"
            className="ms-auto inline-flex items-center h-10 px-4 rounded-ctl bg-brand text-brand-ink text-sm font-medium hover:bg-brand/90 focus:outline-2 focus:outline-offset-2 focus:outline-brand"
          >
            {t('admin.users.new')}
          </Link>
        )}
      </div>
      <div className="grid grid-cols-3 gap-4 max-w-3xl">
        <TextField label={t('admin.users.search')} value={filter.q} onChange={(e) => update({ q: e.target.value })} />
        <SelectField
          label={t('admin.users.role')}
          value={filter.roleId}
          onChange={(e) => update({ roleId: e.target.value })}
          options={[
            { value: '', label: t('admin.all') },
            ...(roles.data ?? []).map((r) => ({ value: String(r.id), label: r.name })),
          ]}
        />
        <SelectField
          label={t('admin.users.status')}
          value={filter.active}
          onChange={(e) => update({ active: e.target.value })}
          options={[
            { value: '', label: t('admin.all') },
            { value: 'true', label: t('admin.users.active') },
            { value: 'false', label: t('admin.users.inactive') },
          ]}
        />
      </div>
      {users.isPending ? (
        <p role="status" className="text-mute">
          {t('app.loading')}
        </p>
      ) : users.data && users.data.content.length === 0 ? (
        <p className="text-mute">{t('admin.users.empty')}</p>
      ) : (
        users.data && (
          <>
            <table className="w-full text-sm border-collapse">
              <thead>
                <tr className="text-start border-b border-line">
                  <th className="text-start py-2">{t('admin.users.username')}</th>
                  <th className="text-start">{t('admin.users.fullName')}</th>
                  <th className="text-start">{t('admin.users.role')}</th>
                  <th className="text-start">{t('admin.users.status')}</th>
                  <th className="text-start">{t('admin.users.lastLogin')}</th>
                </tr>
              </thead>
              <tbody>
                {users.data.content.map((u) => (
                  <tr key={u.id} className="border-b border-line">
                    <td className="py-2">
                      <Link to={`/admin/users/${u.id}`} className="text-brand underline underline-offset-4">
                        {u.username}
                      </Link>
                    </td>
                    <td>{u.fullName}</td>
                    <td>{u.role.name}</td>
                    <td>{u.active ? t('admin.users.active') : t('admin.users.inactive')}</td>
                    <td>{u.lastLoginAt ? formatDateTime(u.lastLoginAt) : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pager page={users.data.page} onPage={(page) => setFilter((f) => ({ ...f, page }))} />
          </>
        )
      )}
    </section>
  )
}

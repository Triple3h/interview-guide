import { useCallback, useEffect, useMemo, useState } from 'react';
import { motion } from 'framer-motion';
import {
  AlertCircle,
  Check,
  KeyRound,
  Loader2,
  Minus,
  Pencil,
  Plus,
  Search,
  Trash2,
  UserCog,
} from 'lucide-react';
import Select from '../../components/ui/Select';
import ConfirmDialog from '../../components/ConfirmDialog';
import DeleteConfirmDialog from '../../components/DeleteConfirmDialog';
import Pagination from '../../components/ui/Pagination';
import AdminUserFormModal from '../../components/admin/AdminUserFormModal';
import { adminUserApi } from '../../api/adminUser';
import type {
  AccountStatus,
  AdminUser,
  CreateAdminUserPayload,
  RoleOption,
  UpdateAdminUserPayload,
  UserRoleCode,
} from '../../types/adminUser';

const ROLE_BADGE_CLASS: Record<UserRoleCode, string> = {
  SUPER_ADMIN: 'bg-amber-50 text-amber-600 dark:bg-amber-900/30 dark:text-amber-400 border-amber-300/30 dark:border-amber-400/30',
  ADMIN: 'bg-blue-50 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400 border-blue-300/30 dark:border-blue-400/30',
  USER: 'bg-slate-50 text-slate-600 dark:bg-slate-700 dark:text-slate-300 border-slate-300/30 dark:border-slate-400/30',
};

const STATUS_BADGE_CLASS: Record<AccountStatus, string> = {
  ACTIVE: 'bg-emerald-50 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-400 border-emerald-300/30 dark:border-emerald-400/30',
  DISABLED: 'bg-red-50 text-red-600 dark:bg-red-900/30 dark:text-red-400 border-red-300/30 dark:border-red-400/30',
};

const INPUT_CLASS =
  'w-full px-3.5 py-2 text-sm border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400';

function formatDateTime(value: string | null): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  const pad = (input: number) => String(input).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * 后台用户管理：查看 / 新增 / 编辑 / 删除用户，并为其他用户分配角色与权限
 */
export default function AdminUsersPage() {
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [roleFilter, setRoleFilter] = useState<UserRoleCode | ''>('');
  const [statusFilter, setStatusFilter] = useState<AccountStatus | ''>('');

  const [data, setData] = useState<{ items: AdminUser[]; total: number } | null>(null);
  const [roles, setRoles] = useState<RoleOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  const [formOpen, setFormOpen] = useState(false);
  const [editingUser, setEditingUser] = useState<AdminUser | null>(null);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');

  const [deleteTarget, setDeleteTarget] = useState<AdminUser | null>(null);
  const [deleting, setDeleting] = useState(false);

  const [resetTarget, setResetTarget] = useState<AdminUser | null>(null);
  const [resetValue, setResetValue] = useState('');
  const [resetting, setResetting] = useState(false);
  const [resetError, setResetError] = useState('');

  const loadUsers = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const result = await adminUserApi.page({
        page,
        size: pageSize,
        keyword: keyword || undefined,
        role: roleFilter || undefined,
        status: statusFilter || undefined,
      });
      setData({ items: result.items, total: result.total });
    } catch (error) {
      setData(null);
      setLoadError(error instanceof Error ? error.message : '加载用户列表失败');
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, keyword, roleFilter, statusFilter]);

  useEffect(() => {
    void loadUsers();
  }, [loadUsers]);

  useEffect(() => {
    adminUserApi.roles()
      .then(setRoles)
      .catch(() => setRoles([]));
  }, []);

  const permissionColumns = useMemo(
    () => roles.find(role => role.code === 'SUPER_ADMIN')?.permissions ?? [],
    [roles]
  );

  const handleSearch = () => {
    setPage(0);
    setKeyword(keywordInput.trim());
  };

  const handleOpenCreate = () => {
    setEditingUser(null);
    setFormError('');
    setFormOpen(true);
  };

  const handleOpenEdit = (user: AdminUser) => {
    setEditingUser(user);
    setFormError('');
    setFormOpen(true);
  };

  const handleSubmitForm = async (payload: CreateAdminUserPayload | UpdateAdminUserPayload) => {
    setSaving(true);
    setFormError('');
    try {
      if (editingUser) {
        await adminUserApi.update(editingUser.id, payload as UpdateAdminUserPayload);
      } else {
        await adminUserApi.create(payload as CreateAdminUserPayload);
      }
      setFormOpen(false);
      await loadUsers();
    } catch (error) {
      setFormError(error instanceof Error ? error.message : '保存失败，请重试');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) {
      return;
    }
    setDeleting(true);
    try {
      await adminUserApi.remove(deleteTarget.id);
      setDeleteTarget(null);
      await loadUsers();
    } catch (error) {
      setDeleteTarget(null);
      setLoadError(error instanceof Error ? error.message : '删除失败，请重试');
    } finally {
      setDeleting(false);
    }
  };

  const handleResetPassword = async () => {
    if (!resetTarget || resetValue.trim().length < 6) {
      return;
    }
    setResetting(true);
    setResetError('');
    try {
      await adminUserApi.resetPassword(resetTarget.id, resetValue.trim());
      setResetTarget(null);
      setResetValue('');
      await loadUsers();
    } catch (error) {
      setResetError(error instanceof Error ? error.message : '重置失败，请重试');
    } finally {
      setResetting(false);
    }
  };

  return (
    <div className="max-w-[1200px] mx-auto">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white font-display">用户管理</h1>
          <p className="text-slate-500 dark:text-slate-400 text-sm mt-1">
            查看与维护账号，为其他用户分配角色与权限
          </p>
        </div>
        <motion.button
          whileHover={{ scale: 1.02 }}
          whileTap={{ scale: 0.98 }}
          onClick={handleOpenCreate}
          className="inline-flex items-center gap-1.5 px-4 py-2 rounded-xl bg-primary-500 text-white text-sm font-medium hover:bg-primary-600 transition-all"
        >
          <Plus className="w-4 h-4" />
          新增用户
        </motion.button>
      </div>

      <div className="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700 p-4 mb-4">
        <div className="flex flex-wrap items-center gap-3">
          <div className="relative flex-1 min-w-[200px] max-w-sm">
            <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
            <input
              value={keywordInput}
              onChange={event => setKeywordInput(event.target.value)}
              onKeyDown={event => {
                if (event.key === 'Enter') {
                  handleSearch();
                }
              }}
              placeholder="按昵称或登录账号搜索"
              className={`${INPUT_CLASS} pl-9`}
            />
          </div>
          <Select
            variant="filter"
            className="sm:w-40"
            value={roleFilter}
            onChange={event => {
              setPage(0);
              setRoleFilter(event.target.value as UserRoleCode | '');
            }}
          >
            <option value="">全部角色</option>
            {roles.map(role => (
              <option key={role.code} value={role.code}>{role.label}</option>
            ))}
          </Select>
          <Select
            variant="filter"
            className="sm:w-32"
            value={statusFilter}
            onChange={event => {
              setPage(0);
              setStatusFilter(event.target.value as AccountStatus | '');
            }}
          >
            <option value="">全部状态</option>
            <option value="ACTIVE">启用</option>
            <option value="DISABLED">禁用</option>
          </Select>
          <button
            type="button"
            onClick={handleSearch}
            className="inline-flex items-center gap-1.5 px-4 py-2 border border-slate-200 dark:border-slate-600 rounded-xl text-slate-600 dark:text-slate-300 font-medium hover:bg-slate-50 dark:hover:bg-slate-700 transition-all"
          >
            搜索
          </button>
        </div>
      </div>

      <div className="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700 overflow-hidden">
        {loadError && (
          <div className="m-4 flex items-center gap-2 bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 text-red-600 dark:text-red-400 rounded-xl px-4 py-3 text-sm">
            <AlertCircle className="w-4 h-4 shrink-0" />
            {loadError}
          </div>
        )}

        {loading ? (
          <div className="flex justify-center py-24">
            <Loader2 className="w-8 h-8 animate-spin text-primary-500" />
          </div>
        ) : !data || data.items.length === 0 ? (
          <div className="text-center py-16 text-slate-400 dark:text-slate-500">
            <UserCog className="w-12 h-12 mx-auto mb-3 opacity-50" />
            <p className="text-sm mb-2">没有匹配的用户</p>
            <p className="text-xs">调整筛选条件，或点击「新增用户」创建账号</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[880px] table-fixed text-sm">
              <colgroup>
                <col className="w-[24%]" />
                <col className="w-[12%]" />
                <col className="w-[10%]" />
                <col className="w-[16%]" />
                <col className="w-[16%]" />
                <col className="w-[22%]" />
              </colgroup>
              <thead className="bg-slate-50 dark:bg-slate-700 text-slate-500 dark:text-slate-300">
                <tr>
                  <th className="px-4 py-3 text-left font-medium">用户</th>
                  <th className="px-4 py-3 text-left font-medium">角色</th>
                  <th className="px-4 py-3 text-left font-medium">状态</th>
                  <th className="px-4 py-3 text-left font-medium">最近登录</th>
                  <th className="px-4 py-3 text-left font-medium">创建时间</th>
                  <th className="px-4 py-3 text-right font-medium">操作</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 dark:divide-slate-700">
                {data.items.map(user => (
                  <tr key={user.id} className="hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors">
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2 min-w-0">
                        <span className="truncate font-medium text-slate-900 dark:text-white">
                          {user.nickname}
                        </span>
                        <span className="truncate text-xs text-slate-400 dark:text-slate-500">
                          {user.username ? `@${user.username}` : '未设置账号'}
                        </span>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`inline-flex items-center px-2 py-0.5 rounded-full border text-xs ${ROLE_BADGE_CLASS[user.role]}`}>
                        {user.roleLabel}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`inline-flex items-center px-2 py-0.5 rounded-full border text-xs ${STATUS_BADGE_CLASS[user.status]}`}>
                        {user.status === 'ACTIVE' ? '启用' : '禁用'}
                      </span>
                    </td>
                    <td className="px-4 py-3 truncate text-slate-500 dark:text-slate-400">
                      {formatDateTime(user.lastLoginAt)}
                    </td>
                    <td className="px-4 py-3 truncate text-slate-500 dark:text-slate-400">
                      {formatDateTime(user.createdAt)}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          type="button"
                          onClick={() => handleOpenEdit(user)}
                          title="编辑用户"
                          className="p-2 rounded-lg text-slate-400 hover:text-primary-600 hover:bg-primary-50 dark:hover:bg-primary-900/30 transition-colors"
                        >
                          <Pencil className="w-4 h-4" />
                        </button>
                        <button
                          type="button"
                          onClick={() => {
                            setResetTarget(user);
                            setResetValue('');
                            setResetError('');
                          }}
                          title="重置密码"
                          className="p-2 rounded-lg text-slate-400 hover:text-primary-600 hover:bg-primary-50 dark:hover:bg-primary-900/30 transition-colors"
                        >
                          <KeyRound className="w-4 h-4" />
                        </button>
                        <span className="w-px h-4 bg-slate-200 dark:bg-slate-600 mx-1" />
                        <button
                          type="button"
                          onClick={() => setDeleteTarget(user)}
                          title="删除用户"
                          className="p-2 rounded-lg text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 transition-colors"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {data && data.total > 0 && (
          <div className="px-4 pb-4">
            <Pagination
              page={page}
              pageSize={pageSize}
              total={data.total}
              unit="个账号"
              onPageChange={setPage}
              onPageSizeChange={size => {
                setPage(0);
                setPageSize(size);
              }}
            />
          </div>
        )}
      </div>

      {permissionColumns.length > 0 && (
        <section className="mt-6 bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700 p-4 md:p-6">
          <h2 className="text-base font-semibold text-slate-900 dark:text-white">角色与权限</h2>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">
            权限由角色决定：调整用户角色即完成权限的授予与收回。授予管理员及以上角色、删除用户、重置密码只有超级管理员可以执行。
          </p>
          <div className="mt-4 overflow-x-auto">
            <table className="w-full min-w-[720px] text-sm">
              <thead className="bg-slate-50 dark:bg-slate-700 text-slate-500 dark:text-slate-300">
                <tr>
                  <th className="px-4 py-2.5 text-left font-medium">权限</th>
                  {roles.map(role => (
                    <th key={role.code} className="px-4 py-2.5 text-center font-medium whitespace-nowrap">
                      {role.label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 dark:divide-slate-700">
                {permissionColumns.map(permission => (
                  <tr key={permission.code}>
                    <td className="px-4 py-2.5 text-slate-700 dark:text-slate-200">
                      <span className="block">{permission.label}</span>
                      <span className="block text-xs text-slate-400 dark:text-slate-500">
                        {permission.description}
                      </span>
                    </td>
                    {roles.map(role => {
                      const granted = role.permissions.some(item => item.code === permission.code);
                      return (
                        <td key={role.code} className="px-4 py-2.5 text-center">
                          {granted ? (
                            <Check className="w-4 h-4 mx-auto text-emerald-600 dark:text-emerald-400" />
                          ) : (
                            <Minus className="w-4 h-4 mx-auto text-slate-300 dark:text-slate-600" />
                          )}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      <AdminUserFormModal
        open={formOpen}
        user={editingUser}
        roles={roles}
        saving={saving}
        error={formError}
        onSubmit={handleSubmitForm}
        onCancel={() => setFormOpen(false)}
      />

      <ConfirmDialog
        open={resetTarget !== null}
        title="重置密码"
        message={resetTarget ? `为「${resetTarget.nickname}」设置新密码，重置后该账号需要重新登录。` : ''}
        confirmText="确定重置"
        confirmVariant="warning"
        loading={resetting}
        onConfirm={handleResetPassword}
        onCancel={() => {
          setResetTarget(null);
          setResetValue('');
          setResetError('');
        }}
        customContent={
          <div className="mt-3">
            <input
              type="password"
              value={resetValue}
              onChange={event => setResetValue(event.target.value)}
              placeholder="新密码，至少 6 位"
              autoComplete="new-password"
              className={INPUT_CLASS}
            />
            {resetError && <p className="mt-2 text-sm text-red-500">{resetError}</p>}
          </div>
        }
      />

      <DeleteConfirmDialog
        open={deleteTarget !== null}
        item={deleteTarget}
        itemType="用户"
        loading={deleting}
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
        customMessage={
          deleteTarget && (
            <>
              确定要删除用户「{deleteTarget.nickname}」吗？删除后该账号无法登录，且无法恢复。
              <span className="block mt-1 text-xs text-slate-500 dark:text-slate-400">
                其历史学习与面试数据仍保留在系统中。
              </span>
            </>
          )
        }
      />
    </div>
  );
}

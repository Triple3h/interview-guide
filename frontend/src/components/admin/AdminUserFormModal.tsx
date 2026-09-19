import { useEffect, useState, type FormEvent } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { X } from 'lucide-react';
import Select from '../ui/Select';
import type {
  AccountStatus,
  AdminUser,
  CreateAdminUserPayload,
  RoleOption,
  UpdateAdminUserPayload,
  UserRoleCode,
} from '../../types/adminUser';

const INPUT_CLASS =
  'w-full px-3.5 py-2 text-sm border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400';

interface AdminUserFormModalProps {
  open: boolean;
  /** 编辑时传入用户，新增时为 null */
  user: AdminUser | null;
  roles: RoleOption[];
  saving: boolean;
  error: string;
  onSubmit: (payload: CreateAdminUserPayload | UpdateAdminUserPayload) => void;
  onCancel: () => void;
}

const EMPTY_FORM = {
  nickname: '',
  username: '',
  password: '',
  role: 'USER' as UserRoleCode,
  status: 'ACTIVE' as AccountStatus,
  occupation: '',
  learningDirection: '',
  currentLevel: '',
};

/**
 * 后台新建 / 编辑用户弹窗
 *
 * 新增时必须给出登录账号与初始密码；编辑时密码不在本弹窗内修改（走「重置密码」）。
 */
export default function AdminUserFormModal({
  open,
  user,
  roles,
  saving,
  error,
  onSubmit,
  onCancel,
}: AdminUserFormModalProps) {
  const [form, setForm] = useState(EMPTY_FORM);

  useEffect(() => {
    if (!open) {
      return;
    }
    setForm(user
      ? {
          nickname: user.nickname,
          username: user.username ?? '',
          password: '',
          role: user.role,
          status: user.status,
          occupation: user.occupation ?? '',
          learningDirection: user.learningDirection ?? '',
          currentLevel: user.currentLevel ?? '',
        }
      : EMPTY_FORM);
  }, [open, user]);

  const setField = <K extends keyof typeof EMPTY_FORM>(key: K, value: string) => {
    setForm(prev => ({ ...prev, [key]: value }));
  };

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (saving) {
      return;
    }

    const profile = {
      occupation: form.occupation.trim(),
      learningDirection: form.learningDirection.trim(),
      currentLevel: form.currentLevel.trim(),
    };

    if (user) {
      const payload: UpdateAdminUserPayload = {
        nickname: form.nickname.trim(),
        username: form.username.trim(),
        role: form.role,
        status: form.status,
        avatarEmoji: user.avatarEmoji ?? '',
        ...profile,
      };
      onSubmit(payload);
      return;
    }

    const payload: CreateAdminUserPayload = {
      nickname: form.nickname.trim(),
      username: form.username.trim(),
      password: form.password,
      role: form.role,
      status: form.status,
      ...profile,
    };
    onSubmit(payload);
  };

  const roleDescription = roles.find(item => item.code === form.role)?.description;

  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onCancel}
            className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
          />
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 20 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 20 }}
              transition={{ duration: 0.2 }}
              onClick={event => event.stopPropagation()}
              className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl w-full max-w-lg max-h-[85vh] md:max-h-[90vh] flex flex-col"
            >
              <div className="flex items-center justify-between px-4 pt-4 pb-2 md:px-6 md:pt-6">
                <h3 className="text-xl md:text-2xl font-bold text-slate-900 dark:text-white">
                  {user ? '编辑用户' : '新增用户'}
                </h3>
                <button
                  type="button"
                  onClick={onCancel}
                  title="关闭"
                  className="p-2 rounded-xl text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-700 dark:hover:text-slate-200 transition-colors"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>

              <form onSubmit={handleSubmit} className="flex flex-col min-h-0">
                <div className="px-4 md:px-6 overflow-y-auto">
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                    <label className="block">
                      <span className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                        昵称
                      </span>
                      <input
                        value={form.nickname}
                        onChange={event => setField('nickname', event.target.value)}
                        placeholder="小辉"
                        className={INPUT_CLASS}
                      />
                    </label>

                    <label className="block">
                      <span className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                        登录账号
                      </span>
                      <input
                        value={form.username}
                        onChange={event => setField('username', event.target.value)}
                        placeholder="登录用的账号"
                        autoComplete="off"
                        className={INPUT_CLASS}
                      />
                    </label>

                    {!user && (
                      <label className="block">
                        <span className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                          初始密码
                        </span>
                        <input
                          type="password"
                          value={form.password}
                          onChange={event => setField('password', event.target.value)}
                          placeholder="至少 6 位"
                          autoComplete="new-password"
                          className={INPUT_CLASS}
                        />
                      </label>
                    )}

                    <div>
                      <span className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                        角色
                      </span>
                      <Select
                        value={form.role}
                        onChange={event => setField('role', event.target.value as UserRoleCode)}
                      >
                        {roles.map(role => (
                          <option key={role.code} value={role.code}>
                            {role.label}
                          </option>
                        ))}
                      </Select>
                    </div>

                    <div>
                      <span className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                        账号状态
                      </span>
                      <Select
                        value={form.status}
                        onChange={event => setField('status', event.target.value as AccountStatus)}
                      >
                        <option value="ACTIVE">启用</option>
                        <option value="DISABLED">禁用</option>
                      </Select>
                    </div>

                    <label className="block">
                      <span className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                        职业
                      </span>
                      <input
                        value={form.occupation}
                        onChange={event => setField('occupation', event.target.value)}
                        placeholder="选填"
                        className={INPUT_CLASS}
                      />
                    </label>

                    <label className="block">
                      <span className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                        学习方向
                      </span>
                      <input
                        value={form.learningDirection}
                        onChange={event => setField('learningDirection', event.target.value)}
                        placeholder="选填"
                        className={INPUT_CLASS}
                      />
                    </label>

                    <label className="block sm:col-span-2">
                      <span className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                        当前水平
                      </span>
                      <input
                        value={form.currentLevel}
                        onChange={event => setField('currentLevel', event.target.value)}
                        placeholder="选填"
                        className={INPUT_CLASS}
                      />
                    </label>
                  </div>

                  {roleDescription && (
                    <p className="mt-3 text-xs text-slate-500 dark:text-slate-400">{roleDescription}</p>
                  )}
                  {user && (
                    <p className="mt-1 text-xs text-slate-400 dark:text-slate-500">
                      修改密码请用列表中的「重置密码」
                    </p>
                  )}
                  {error && <p className="mt-3 text-sm text-red-500">{error}</p>}
                </div>

                <div className="flex gap-2 md:gap-3 md:justify-end px-4 py-4 md:px-6">
                  <button
                    type="button"
                    onClick={onCancel}
                    disabled={saving}
                    className="flex-1 md:flex-none px-5 py-2.5 border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 rounded-xl font-medium hover:bg-slate-50 dark:hover:bg-slate-700 transition-all disabled:opacity-50"
                  >
                    取消
                  </button>
                  <button
                    type="submit"
                    disabled={saving || !form.nickname.trim() || !form.username.trim()
                      || (!user && !form.password.trim())}
                    className="flex-1 md:flex-none px-5 py-2.5 rounded-xl bg-primary-500 text-white text-sm font-medium hover:bg-primary-600 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {saving ? '保存中…' : '保存'}
                  </button>
                </div>
              </form>
            </motion.div>
          </div>
        </>
      )}
    </AnimatePresence>
  );
}

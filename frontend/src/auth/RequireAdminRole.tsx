import { Outlet } from 'react-router-dom';
import { ShieldAlert } from 'lucide-react';
import { useAuth } from './AuthContext';

/**
 * 角色守卫：只有管理员 / 超级管理员能进入其下的路由
 *
 * 后台与学员端已合并为一套登录态，因此这里不再校验「是否登录」（由 RequireAuth 负责），
 * 只校验角色；角色不足时给出明确提示与返回入口，避免直接把人踢回登录页。
 */
export default function RequireAdminRole() {
  const { status, isAdmin, role } = useAuth();

  if (status === 'loading') {
    return (
      <div className="flex items-center justify-center min-h-dvh">
        <div className="w-10 h-10 border-3 border-slate-200 border-t-primary-500 rounded-full animate-spin" />
      </div>
    );
  }

  if (!isAdmin) {
    return (
      <div className="max-w-xl mx-auto pt-16 pb-20 px-4 text-center">
        <div className="w-12 h-12 rounded-2xl bg-amber-50 dark:bg-amber-900/30 flex items-center justify-center mx-auto mb-3">
          <ShieldAlert className="w-6 h-6 text-amber-600 dark:text-amber-400" />
        </div>
        <h1 className="text-xl font-bold text-slate-900 dark:text-white mb-1">没有访问权限</h1>
        <p className="text-sm text-slate-500 dark:text-slate-400">
          当前角色{role ? `（${role}）` : ''}无法进入管理页面，请联系超级管理员为你分配管理员角色。
        </p>
      </div>
    );
  }

  return <Outlet />;
}

import {Users} from 'lucide-react';

/**
 * 用户管理页（P4 实现：列表 / 建号 / 编辑 / 禁用 / 重置密码）
 * P3 先占位，保证后台路由骨架可访问
 */
export default function AdminUsersPage() {
  return (
    <div className="max-w-5xl">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-slate-900 dark:text-white font-display">用户管理</h1>
        <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">创建学员账号、重置密码、禁用账号</p>
      </div>

      <div className="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700 p-10 text-center">
        <div className="w-12 h-12 rounded-2xl bg-slate-50 dark:bg-slate-700 flex items-center justify-center mx-auto mb-3">
          <Users className="w-6 h-6 text-slate-400" />
        </div>
        <p className="text-slate-600 dark:text-slate-300 text-sm">用户管理功能开发中</p>
        <p className="text-slate-400 dark:text-slate-500 text-xs mt-1">将在此处管理学员账号与登录状态</p>
      </div>
    </div>
  );
}

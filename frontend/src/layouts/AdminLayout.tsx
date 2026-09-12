import {Link, Outlet, useLocation, useNavigate} from 'react-router-dom';
import {LogOut, MonitorSmartphone, ShieldCheck, Users} from 'lucide-react';
import {useAdminAuth} from '../auth/AdminAuthContext';

interface AdminNavItem {
  path: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  description?: string;
}

const ADMIN_NAV_ITEMS: AdminNavItem[] = [
  { path: '/admin/users', label: '用户管理', icon: Users, description: '学员账号与密码' },
];

/**
 * 后台 Layout：PC 侧栏骨架，小屏提示用电脑访问
 * 后续（P4）把知识库 / 题库 / 设置等管理页迁入本 Layout
 */
export default function AdminLayout() {
  const {logout} = useAdminAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const handleLogout = () => {
    logout();
    navigate('/admin/login', {replace: true});
  };

  return (
    <>
      {/* 手机端：管理后台不做移动端适配 */}
      <div className="md:hidden min-h-dvh flex flex-col items-center justify-center gap-4 px-8 text-center bg-[var(--ov-bg)]">
        <MonitorSmartphone className="w-10 h-10 text-slate-400" />
        <p className="text-sm text-slate-600 dark:text-slate-300 leading-relaxed">
          管理后台需要更大的屏幕
          <br />
          请使用电脑访问
        </p>
        <div className="flex items-center gap-3">
          <Link
            to="/"
            className="px-4 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300"
          >
            返回学员端
          </Link>
          <button
            onClick={handleLogout}
            className="px-4 py-2 text-sm rounded-lg bg-slate-800 dark:bg-slate-600 text-white"
          >
            退出登录
          </button>
        </div>
      </div>

      {/* 桌面端：侧栏 + 主区 */}
      <div className="hidden md:flex min-h-screen bg-[var(--ov-bg)]">
        <aside className="w-60 bg-[var(--ov-bg-soft)] border-r border-[var(--ov-border-soft)] fixed h-screen left-0 top-0 z-40 flex-col flex">
          <div className="p-5 border-b border-[var(--ov-border-soft)] flex items-center gap-3">
            <div className="w-9 h-9 bg-slate-800 dark:bg-slate-600 rounded-lg flex items-center justify-center text-white flex-shrink-0">
              <ShieldCheck className="w-5 h-5" />
            </div>
            <div className="min-w-0">
              <span className="text-base font-bold text-slate-900 dark:text-slate-50 tracking-tight block font-display">
                管理后台
              </span>
              <span className="ov-label">Admin Console</span>
            </div>
          </div>

          <nav className="flex-1 p-3 space-y-1">
            {ADMIN_NAV_ITEMS.map((item) => {
              const active = location.pathname.startsWith(item.path);

              return (
                <Link
                  key={item.path}
                  to={item.path}
                  className={`flex items-center gap-3 px-3 py-2.5 rounded-lg border transition-colors
                    ${active
                      ? 'bg-[var(--ov-accent-soft-bg)] border-[var(--ov-accent-border)] text-primary-700 dark:text-primary-300'
                      : 'border-transparent text-slate-600 dark:text-slate-400 hover:bg-[var(--ov-muted)] hover:text-slate-900 dark:hover:text-slate-100'
                    }`}
                >
                  <div className={`w-8 h-8 rounded-md flex items-center justify-center
                    ${active
                      ? 'bg-primary-600 dark:bg-primary-500 text-white'
                      : 'bg-[var(--ov-panel)] border border-[var(--ov-border-soft)] text-slate-500 dark:text-slate-400'
                    }`}
                  >
                    <item.icon className="w-4 h-4" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <span className={`text-sm block ${active ? 'font-semibold' : 'font-medium'}`}>{item.label}</span>
                    {item.description && (
                      <span className="text-xs text-slate-500 dark:text-slate-500 truncate block">
                        {item.description}
                      </span>
                    )}
                  </div>
                </Link>
              );
            })}
          </nav>

          <div className="p-3 border-t border-[var(--ov-border-soft)] space-y-1">
            <Link
              to="/"
              className="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm text-slate-600 dark:text-slate-400 hover:bg-[var(--ov-muted)] transition-colors"
            >
              <span className="w-8 h-8 rounded-md flex items-center justify-center bg-[var(--ov-panel)] border border-[var(--ov-border-soft)] text-slate-500 dark:text-slate-400">
                <MonitorSmartphone className="w-4 h-4" />
              </span>
              <span>前往学员端</span>
            </Link>
            <button
              onClick={handleLogout}
              className="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm text-slate-600 dark:text-slate-400 hover:bg-[var(--ov-muted)] hover:text-red-500 transition-colors"
            >
              <span className="w-8 h-8 rounded-md flex items-center justify-center bg-[var(--ov-panel)] border border-[var(--ov-border-soft)] text-slate-500 dark:text-slate-400">
                <LogOut className="w-4 h-4" />
              </span>
              <span>退出登录</span>
            </button>
          </div>
        </aside>

        <main className="flex-1 ml-60 p-8 min-h-screen">
          <Outlet />
        </main>
      </div>
    </>
  );
}

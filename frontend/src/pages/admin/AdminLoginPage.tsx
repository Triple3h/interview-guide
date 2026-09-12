import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { KeyRound, Loader2, ShieldCheck } from 'lucide-react';
import { useAdminAuth } from '../../auth/AdminAuthContext';

/**
 * 管理端登录页：输入固定 Token（服务器 .env 的 APP_ADMIN_TOKEN）
 */
export default function AdminLoginPage() {
  const { status, login } = useAdminAuth();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const redirect = searchParams.get('redirect') || '/admin/users';

  const [token, setToken] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  if (status === 'authenticated') {
    return <Navigate to={redirect} replace />;
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!token.trim() || submitting) {
      return;
    }

    setSubmitting(true);
    setError('');
    try {
      await login(token);
      navigate(redirect, { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败，请重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="min-h-dvh flex items-center justify-center px-4 py-10 bg-[var(--ov-bg)]">
      <div className="w-full max-w-sm">
        <div className="text-center mb-6">
          <div className="w-12 h-12 bg-slate-800 dark:bg-slate-700 rounded-xl flex items-center justify-center text-white mx-auto mb-3">
            <ShieldCheck className="w-6 h-6" />
          </div>
          <h1 className="text-xl font-bold text-slate-900 dark:text-slate-50 tracking-tight font-display">
            管理后台
          </h1>
          <p className="text-sm text-slate-500 dark:text-slate-400 mt-1">输入管理端 Token 后进入</p>
        </div>

        <form
          onSubmit={handleSubmit}
          className="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700 p-6"
        >
          <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5" htmlFor="admin-token">
            管理端 Token
          </label>
          <div className="relative">
            <KeyRound className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
            <input
              id="admin-token"
              type="password"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder="APP_ADMIN_TOKEN"
              autoComplete="off"
              autoFocus
              className="w-full pl-9 pr-3 py-2.5 text-sm border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
            />
          </div>

          {error && <p className="mt-4 text-sm text-red-500">{error}</p>}

          <button
            type="submit"
            disabled={!token.trim() || submitting}
            className="mt-5 w-full flex items-center justify-center gap-2 px-4 py-2.5 text-sm font-medium bg-slate-800 dark:bg-slate-600 text-white rounded-xl hover:bg-slate-900 dark:hover:bg-slate-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          >
            {submitting && <Loader2 className="w-4 h-4 animate-spin" />}
            {submitting ? '验证中…' : '进入后台'}
          </button>

          <p className="mt-4 text-xs text-slate-400 dark:text-slate-500 leading-relaxed">
            Token 由运维在服务器 <code className="font-mono">.env</code> 的{' '}
            <code className="font-mono">APP_ADMIN_TOKEN</code> 配置，修改后需重启应用。
          </p>
        </form>

        <p className="mt-4 text-center text-xs text-slate-400 dark:text-slate-500">
          <Link to="/login" className="hover:text-primary-600 dark:hover:text-primary-400 transition-colors">
            返回学员登录
          </Link>
        </p>
      </div>
    </div>
  );
}

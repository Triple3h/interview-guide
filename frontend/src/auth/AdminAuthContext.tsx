import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { adminAuthApi } from '../api/auth';
import { clearStoredToken, readStoredToken, writeStoredToken } from './tokenStore';

export type AdminAuthStatus = 'loading' | 'authenticated' | 'anonymous';

export interface AdminAuthContextValue {
  status: AdminAuthStatus;
  login: (token: string) => Promise<void>;
  logout: () => void;
}

const AdminAuthContext = createContext<AdminAuthContextValue | null>(null);

/**
 * 管理端登录态：localStorage 保存 token，启动时用 /api/admin/ping 校验有效性
 *
 * 管理端没有账号体系（管理员 = 持有 APP_ADMIN_TOKEN），登出仅清本地 token。
 */
export function AdminAuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AdminAuthStatus>(() => (readStoredToken('admin') ? 'loading' : 'anonymous'));

  useEffect(() => {
    if (status !== 'loading') {
      return;
    }

    let cancelled = false;
    adminAuthApi.ping()
      .then(() => {
        if (!cancelled) {
          setStatus('authenticated');
        }
      })
      .catch(() => {
        if (!cancelled) {
          clearStoredToken('admin');
          setStatus('anonymous');
        }
      });

    return () => {
      cancelled = true;
    };
  }, [status]);

  const login = useCallback(async (token: string) => {
    const response = await adminAuthApi.login({ token: token.trim() });
    writeStoredToken('admin', { token: response.token, tokenName: response.tokenName });
    setStatus('authenticated');
  }, []);

  const logout = useCallback(() => {
    clearStoredToken('admin');
    setStatus('anonymous');
  }, []);

  const value = useMemo<AdminAuthContextValue>(() => ({ status, login, logout }), [status, login, logout]);

  return <AdminAuthContext.Provider value={value}>{children}</AdminAuthContext.Provider>;
}

export function useAdminAuth(): AdminAuthContextValue {
  const context = useContext(AdminAuthContext);
  if (!context) {
    throw new Error('useAdminAuth 必须在 AdminAuthProvider 内使用');
  }
  return context;
}

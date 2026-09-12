import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { authApi } from '../api/auth';
import { clearStoredToken, readStoredToken, writeStoredToken } from './tokenStore';
import type { UserProfile } from '../types/user';

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous';

export interface AuthContextValue {
  status: AuthStatus;
  /** 当前登录学员完整资料（未登录为 null） */
  profile: UserProfile | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  /** 拉取最新资料（改资料后用）；失败返回 null */
  refreshProfile: () => Promise<UserProfile | null>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * 学员登录态：localStorage 保存 token，启动时用 /api/auth/me 校验有效性
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>(() => (readStoredToken('student') ? 'loading' : 'anonymous'));
  const [profile, setProfile] = useState<UserProfile | null>(null);

  useEffect(() => {
    if (status !== 'loading') {
      return;
    }

    let cancelled = false;
    authApi.me()
      .then((fresh) => {
        if (cancelled) {
          return;
        }
        setProfile(fresh);
        setStatus('authenticated');
      })
      .catch(() => {
        if (cancelled) {
          return;
        }
        clearStoredToken('student');
        setStatus('anonymous');
      });

    return () => {
      cancelled = true;
    };
  }, [status]);

  const login = useCallback(async (username: string, password: string) => {
    const response = await authApi.login({ username, password });
    writeStoredToken('student', { token: response.token, tokenName: response.tokenName });
    try {
      const fresh = await authApi.me();
      setProfile(fresh);
      setStatus('authenticated');
    } catch (error) {
      // 换 token 后仍拿不到资料：按登录失败处理，避免留下半截状态
      clearStoredToken('student');
      throw error;
    }
  }, []);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch {
      // 网络异常也要清本地登录态
    }
    clearStoredToken('student');
    setProfile(null);
    setStatus('anonymous');
  }, []);

  const refreshProfile = useCallback(async () => {
    try {
      const fresh = await authApi.me();
      setProfile(fresh);
      return fresh;
    } catch {
      return null;
    }
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ status, profile, login, logout, refreshProfile }),
    [status, profile, login, logout, refreshProfile],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth 必须在 AuthProvider 内使用');
  }
  return context;
}

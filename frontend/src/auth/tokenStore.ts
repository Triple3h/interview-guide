/**
 * 登录态 token 存取与请求头选择（SA-Token 双账号体系）
 *
 * - 学员端：localStorage `auth.token`，其余 /api/** 请求携带
 * - 管理端：localStorage `admin.token`，仅 /api/admin/** 请求携带
 *
 * 纯函数（isAdminApiUrl / parseStoredToken / pickAuthToken / buildAuthHeader）与浏览器
 * 存储读写分离，便于 node --test 直接单测。
 */

export const STUDENT_TOKEN_KEY = 'auth.token';
export const ADMIN_TOKEN_KEY = 'admin.token';
/** 与后端 sa-token.token-name 的默认值一致；登录响应会回传实际值 */
export const DEFAULT_TOKEN_HEADER = 'sa-token';

export type AuthScope = 'student' | 'admin';

export interface StoredToken {
  token: string;
  /** 后端 sa-token.token-name，登录时回传；缺省 sa-token */
  tokenName: string;
}

export interface LoginTokenPayload {
  token: string;
  tokenName?: string;
}

/** /api/admin/** 判定（忽略查询串） */
export function isAdminApiUrl(url?: string): boolean {
  if (!url) {
    return false;
  }
  const path = url.split('?')[0];
  return path === '/api/admin' || path.startsWith('/api/admin/');
}

export function parseStoredToken(raw: string | null): StoredToken | null {
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as Partial<StoredToken>;
    if (typeof parsed.token === 'string' && parsed.token) {
      return {
        token: parsed.token,
        tokenName: typeof parsed.tokenName === 'string' && parsed.tokenName
          ? parsed.tokenName
          : DEFAULT_TOKEN_HEADER,
      };
    }
  } catch {
    // 本地数据损坏时按未登录处理
  }
  return null;
}

/** 按目标 URL 选择应使用的登录态：管理端接口用 admin，其余用学员 */
export function pickAuthToken(
  url: string | undefined,
  student: StoredToken | null,
  admin: StoredToken | null,
): StoredToken | null {
  return isAdminApiUrl(url) ? admin : student;
}

export function buildAuthHeader(
  url: string | undefined,
  student: StoredToken | null,
  admin: StoredToken | null,
): Record<string, string> {
  const picked = pickAuthToken(url, student, admin);
  if (!picked) {
    return {};
  }
  return { [picked.tokenName]: picked.token };
}

function storageKey(scope: AuthScope): string {
  return scope === 'admin' ? ADMIN_TOKEN_KEY : STUDENT_TOKEN_KEY;
}

export function readStoredToken(scope: AuthScope): StoredToken | null {
  try {
    return parseStoredToken(localStorage.getItem(storageKey(scope)));
  } catch {
    return null;
  }
}

export function writeStoredToken(scope: AuthScope, payload: LoginTokenPayload): void {
  try {
    const stored: StoredToken = {
      token: payload.token,
      tokenName: payload.tokenName || DEFAULT_TOKEN_HEADER,
    };
    localStorage.setItem(storageKey(scope), JSON.stringify(stored));
  } catch {
    // 隐私模式等存储不可用场景：忽略，仅本次会话内存态生效
  }
}

export function clearStoredToken(scope: AuthScope): void {
  try {
    localStorage.removeItem(storageKey(scope));
  } catch {
    // 忽略
  }
}

/** axios 拦截器与 fetch（SSE）共用的鉴权头 */
export function getAuthHeaderForUrl(url?: string): Record<string, string> {
  return buildAuthHeader(url, readStoredToken('student'), readStoredToken('admin'));
}

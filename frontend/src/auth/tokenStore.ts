/**
 * 登录态 token 存取与请求头（全站单一登录态）
 *
 * - 学员与管理员共用同一份登录态：localStorage `auth.token`
 * - 能否进后台由账号角色决定（见 auth/AuthContext 的 role），不再区分接口前缀
 *
 * 纯函数（parseStoredToken / buildAuthHeader）与浏览器存储读写分离，
 * 便于 node --test 直接单测。
 */

export const TOKEN_KEY = 'auth.token';
/** 与后端 sa-token.token-name 的默认值一致；登录响应会回传实际值 */
export const DEFAULT_TOKEN_HEADER = 'sa-token';

export interface StoredToken {
  token: string;
  /** 后端 sa-token.token-name，登录时回传；缺省 sa-token */
  tokenName: string;
}

export interface LoginTokenPayload {
  token: string;
  tokenName?: string;
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

export function buildAuthHeader(token: StoredToken | null): Record<string, string> {
  if (!token) {
    return {};
  }
  return { [token.tokenName]: token.token };
}

export function readStoredToken(): StoredToken | null {
  try {
    return parseStoredToken(localStorage.getItem(TOKEN_KEY));
  } catch {
    return null;
  }
}

export function writeStoredToken(payload: LoginTokenPayload): void {
  try {
    const stored: StoredToken = {
      token: payload.token,
      tokenName: payload.tokenName || DEFAULT_TOKEN_HEADER,
    };
    localStorage.setItem(TOKEN_KEY, JSON.stringify(stored));
  } catch {
    // 隐私模式等存储不可用场景：忽略，仅本次会话内存态生效
  }
}

export function clearStoredToken(): void {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    // 忽略
  }
}

/** axios 拦截器与 fetch（SSE）共用的鉴权头 */
export function getAuthHeader(): Record<string, string> {
  return buildAuthHeader(readStoredToken());
}

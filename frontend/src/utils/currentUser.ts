/**
 * 当前学习成员（极简选人模式）
 * 首次进入选人后写入 localStorage，请求层自动携带 X-User-Id
 */

export const CURRENT_USER_STORAGE_KEY = 'learning.currentUser';

export interface StoredUser {
  id: number;
  nickname: string;
  avatarEmoji?: string | null;
}

export function getStoredUser(): StoredUser | null {
  const raw = localStorage.getItem(CURRENT_USER_STORAGE_KEY);
  if (!raw) {
    return null;
  }

  try {
    const parsed = JSON.parse(raw) as Partial<StoredUser>;
    if (typeof parsed.id === 'number' && typeof parsed.nickname === 'string') {
      return {
        id: parsed.id,
        nickname: parsed.nickname,
        avatarEmoji: parsed.avatarEmoji ?? null,
      };
    }
  } catch {
    // 数据损坏时按未选择处理
  }
  return null;
}

export function getStoredUserId(): number | null {
  return getStoredUser()?.id ?? null;
}

export function storeUser(user: StoredUser): void {
  localStorage.setItem(CURRENT_USER_STORAGE_KEY, JSON.stringify(user));
}

export function clearStoredUser(): void {
  localStorage.removeItem(CURRENT_USER_STORAGE_KEY);
}

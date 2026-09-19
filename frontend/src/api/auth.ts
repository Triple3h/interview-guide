import { request } from './request';
import type { UserProfile } from '../types/user';

/** 登录后返回的资料摘要（完整资料由 /api/auth/me 拉取） */
export interface LoginProfile {
  id: number;
  username: string | null;
  nickname: string;
  avatarEmoji: string | null;
  role: string;
  roleLabel: string;
}

export interface LoginResponse {
  token: string;
  tokenName: string;
  profile: LoginProfile;
}

export interface ChangePasswordPayload {
  oldPassword: string;
  newPassword: string;
}

export const authApi = {
  /** 学员登录：用户名 + 密码 */
  async login(payload: { username: string; password: string }): Promise<LoginResponse> {
    return request.post<LoginResponse>('/api/auth/login', payload, { skipAuthRedirect: true });
  },

  /** 退出登录：清除服务端登录态（本地 token 由调用方清理） */
  async logout(): Promise<void> {
    return request.post<void>('/api/auth/logout');
  },

  /** 当前登录账号完整资料（也用于校验本地 token 是否有效） */
  async me(): Promise<UserProfile> {
    return request.get<UserProfile>('/api/auth/me', { skipAuthRedirect: true });
  },

  /** 修改密码 */
  async changePassword(payload: ChangePasswordPayload): Promise<void> {
    return request.post<void>('/api/auth/change-password', payload);
  },
};

export default authApi;

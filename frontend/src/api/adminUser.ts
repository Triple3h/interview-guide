import { request } from './request';
import type {
  AdminUser,
  AdminUserPage,
  AdminUserPageParams,
  CreateAdminUserPayload,
  RoleOption,
  UpdateAdminUserPayload,
} from '../types/adminUser';

/**
 * 管理端用户管理接口（/api/admin/users，携带管理端 token）
 */
export const adminUserApi = {
  /** 分页查询用户 */
  async page(params: AdminUserPageParams): Promise<AdminUserPage> {
    return request.get<AdminUserPage>('/api/admin/users', { params });
  },

  /** 角色与权限目录 */
  async roles(): Promise<RoleOption[]> {
    return request.get<RoleOption[]>('/api/admin/users/roles');
  },

  async create(payload: CreateAdminUserPayload): Promise<AdminUser> {
    return request.post<AdminUser>('/api/admin/users', payload);
  },

  async update(id: number, payload: UpdateAdminUserPayload): Promise<AdminUser> {
    return request.put<AdminUser>(`/api/admin/users/${id}`, payload);
  },

  async remove(id: number): Promise<void> {
    return request.delete<void>(`/api/admin/users/${id}`);
  },

  /** 重置登录密码 */
  async resetPassword(id: number, newPassword: string): Promise<void> {
    return request.post<void>(`/api/admin/users/${id}/password`, { newPassword });
  },
};

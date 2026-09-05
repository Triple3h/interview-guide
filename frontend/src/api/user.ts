import { request } from './request';
import type { SaveUserPayload, UserProfile } from '../types/user';

export const userApi = {
  /**
   * 所有学习成员（首次进入选人用）
   */
  async list(): Promise<UserProfile[]> {
    return request.get<UserProfile[]>('/api/users');
  },

  async get(id: number): Promise<UserProfile> {
    return request.get<UserProfile>(`/api/users/${id}`);
  },

  async create(payload: SaveUserPayload): Promise<UserProfile> {
    return request.post<UserProfile>('/api/users', payload);
  },

  async update(id: number, payload: SaveUserPayload): Promise<UserProfile> {
    return request.put<UserProfile>(`/api/users/${id}`, payload);
  },
};

import { request } from './request';
import type { LearningRecord, LearningMastery, SaveLearningRecordPayload } from '../types/learning';

export const learningApi = {
  /**
   * 我的台账列表（keyword 同时匹配主题与摘要）
   */
  async list(keyword?: string): Promise<LearningRecord[]> {
    const query = keyword && keyword.trim() ? `?keyword=${encodeURIComponent(keyword.trim())}` : '';
    return request.get<LearningRecord[]>(`/api/learning/records${query}`);
  },

  async create(payload: SaveLearningRecordPayload): Promise<LearningRecord> {
    return request.post<LearningRecord>('/api/learning/records', payload);
  },

  async update(id: number, payload: Partial<SaveLearningRecordPayload>): Promise<LearningRecord> {
    return request.put<LearningRecord>(`/api/learning/records/${id}`, payload);
  },

  async updateMastery(id: number, mastery: LearningMastery): Promise<LearningRecord> {
    return request.put<LearningRecord>(`/api/learning/records/${id}`, { mastery });
  },

  async delete(id: number): Promise<void> {
    return request.delete(`/api/learning/records/${id}`);
  },
};

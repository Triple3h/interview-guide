import { request } from './request';
import type {
  LearningPlanItem,
  LearningPlanStatus,
  LearningRecord,
  LearningMastery,
  SaveLearningRecordPayload,
  SavePlanItemPayload,
} from '../types/learning';

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

  /**
   * 我的当前学习计划（按 sortOrder 排列）
   */
  async listPlan(): Promise<LearningPlanItem[]> {
    return request.get<LearningPlanItem[]>('/api/learning/plans');
  },

  async createPlanItem(payload: SavePlanItemPayload): Promise<LearningPlanItem> {
    return request.post<LearningPlanItem>('/api/learning/plans', payload);
  },

  async updatePlanItem(
    id: number,
    payload: Partial<SavePlanItemPayload> & { status?: LearningPlanStatus }
  ): Promise<LearningPlanItem> {
    return request.put<LearningPlanItem>(`/api/learning/plans/${id}`, payload);
  },

  async updatePlanStatus(id: number, status: LearningPlanStatus): Promise<LearningPlanItem> {
    return request.put<LearningPlanItem>(`/api/learning/plans/${id}/status`, { status });
  },

  async deletePlanItem(id: number): Promise<void> {
    return request.delete(`/api/learning/plans/${id}`);
  },
};

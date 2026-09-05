export type LearningMastery = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED';

export interface LearningRecord {
  id: number;
  topic: string;
  summary: string;
  mastery: LearningMastery;
  masteryLabel: string;
  sourceSessionId: number | null;
  createdAt: string;
  updatedAt: string;
  lastReviewedAt: string | null;
}

export interface SaveLearningRecordPayload {
  topic: string;
  summary: string;
  mastery: LearningMastery;
}

export type LearningPlanStatus = 'PENDING' | 'IN_PROGRESS' | 'DONE';

export interface LearningPlanItem {
  id: number;
  topic: string;
  goal: string | null;
  status: LearningPlanStatus;
  statusLabel: string;
  sortOrder: number;
  sourceSessionId: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface SavePlanItemPayload {
  topic: string;
  goal?: string;
}

/** Agent 向学员发起的选项提问（askLearner 工具） */
export interface AskLearnerPayload {
  question: string;
  options: string[];
}

/** Agent 工具调用步骤（流式返回 + 消息回放共用） */
export interface AgentStep {
  tool: string;
  phase: 'start' | 'end' | 'error';
  summary: string;
  /** 工具结果摘要（仅 end 携带，供展开查看；旧消息无此字段） */
  detail?: string;
}

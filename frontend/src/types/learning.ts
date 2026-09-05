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

/** Agent 工具调用步骤（流式返回 + 消息回放共用） */
export interface AgentStep {
  tool: string;
  phase: 'start' | 'end' | 'error';
  summary: string;
}

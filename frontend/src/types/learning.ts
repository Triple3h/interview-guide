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

/** 一次工具调用（start + end/error 配对后的完整执行记录） */
export interface ToolInvocation {
  tool: string;
  /** 入参摘要（start 事件携带） */
  argsSummary: string;
  status: 'running' | 'ok' | 'error';
  /** 结果摘要（end/error 事件携带） */
  resultSummary: string;
  /** 工具返回原文（供展开查看） */
  detail?: string;
}

/**
 * Agent 回答的时间线片段：思考 / 工具调用 / 正文，按发生顺序排列；
 * 前端按块渲染，不再把工具调用聚合成一个折叠面板（原型见「思考→工具→再思考→正文」）
 */
export type AgentBlock =
  | { kind: 'reasoning'; text: string }
  | { kind: 'tool'; invocation: ToolInvocation }
  | { kind: 'text'; text: string };

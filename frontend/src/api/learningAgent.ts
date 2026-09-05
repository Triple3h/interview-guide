import { request } from './request';
import { streamSse } from './stream';
import { CURRENT_USER_STORAGE_KEY } from '../utils/currentUser';
import type { RagChatSession, RagChatSessionDetail } from './ragChat';
import type { AgentStep, AskLearnerPayload } from '../types/learning';

export type { RagChatSession, RagChatSessionDetail };

export interface AgentStreamHandlers {
  onStep: (step: AgentStep) => void;
  onDelta: (text: string) => void;
  onReasoning: (text: string) => void;
  /** Agent 发起的选项提问（askLearner 工具），回答经 answerAsk 回流 */
  onAsk: (payload: AskLearnerPayload) => void;
  /** 首轮回答结束后后端自动生成的会话标题 */
  onTitle?: (title: string) => void;
  onComplete: () => void;
  onError: (error: Error) => void;
}

interface AgentEventPayload {
  type?: string;
  text?: string;
  tool?: string;
  phase?: string;
  summary?: string;
  detail?: string;
  message?: string;
  question?: string;
  options?: string[];
}

export const learningAgentApi = {
  /**
   * 创建学习会话（不绑定知识库，由 Agent 自主检索）
   */
  async createSession(title?: string): Promise<RagChatSession> {
    return request.post<RagChatSession>('/api/learning/sessions', { title });
  },

  /**
   * 学习会话流式问答（SSE 类型化事件：delta/step/reasoning/error）
   */
  async streamChat(
    sessionId: number,
    question: string,
    handlers: AgentStreamHandlers
  ): Promise<void> {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    const raw = localStorage.getItem(CURRENT_USER_STORAGE_KEY);
    if (raw) {
      try {
        const stored = JSON.parse(raw) as { id?: number };
        if (typeof stored.id === 'number') {
          headers['X-User-Id'] = String(stored.id);
        }
      } catch {
        // 未选人时后端会返回统一错误
      }
    }

    return streamSse({
      url: `/api/learning/sessions/${sessionId}/stream`,
      init: {
        method: 'POST',
        headers,
        body: JSON.stringify({ question }),
      },
      onMessage: (chunk: string) => {
        let event: AgentEventPayload;
        try {
          event = JSON.parse(chunk) as AgentEventPayload;
        } catch {
          return;
        }

        if (event.type === 'delta' && typeof event.text === 'string') {
          handlers.onDelta(event.text);
        } else if (event.type === 'reasoning' && typeof event.text === 'string') {
          handlers.onReasoning(event.text);
        } else if (event.type === 'ask' && typeof event.question === 'string') {
          handlers.onAsk({ question: event.question, options: event.options ?? [] });
        } else if (event.type === 'title' && typeof event.text === 'string') {
          handlers.onTitle?.(event.text);
        } else if (event.type === 'step') {
          handlers.onStep({
            tool: event.tool ?? 'unknown',
            phase: (event.phase === 'end' || event.phase === 'error') ? event.phase : 'start',
            summary: event.summary ?? '',
            detail: event.detail,
          });
        }
        // error 类型事件由 streamSse 统一抛给 onError
      },
      onComplete: handlers.onComplete,
      onError: handlers.onError,
      parseMode: 'event',
      trimDataPrefixSpace: false,
      unescapeEscapedNewlines: false,
      dataJoiner: '',
    });
  },

  /**
   * 提交学员对 askLearner 提问的回答（后端放行阻塞中的 Agent 工具线程）
   */
  async answerAsk(sessionId: number, answer: string): Promise<void> {
    return request.post<void>(`/api/learning/sessions/${sessionId}/ask-answer`, { answer });
  },
};

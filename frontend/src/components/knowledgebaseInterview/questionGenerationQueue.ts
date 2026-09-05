import type { QuestionGenStatus, QuestionGenStatusResponse } from '../../api/knowledgebase';

export interface GenerationQueueSummary {
  total: number;
  queued: number;
  processing: number;
  completed: number;
  failed: number;
  /** 已出结果的数量（完成 + 失败） */
  finished: number;
  /** 是否仍有排队或生成中的任务 */
  active: boolean;
}

/** 队列行排序权重：进行中最靠前，失败次之（便于先处理），完成后再次之 */
const STATUS_ORDER: Record<QuestionGenStatus, number> = {
  PROCESSING: 0,
  QUEUED: 1,
  FAILED: 2,
  COMPLETED: 3,
  NONE: 4,
};

export function summarizeGenerationQueue(
  statuses: QuestionGenStatusResponse[]
): GenerationQueueSummary {
  const summary: GenerationQueueSummary = {
    total: statuses.length,
    queued: 0,
    processing: 0,
    completed: 0,
    failed: 0,
    finished: 0,
    active: false,
  };
  for (const status of statuses) {
    switch (status.questionGenStatus) {
      case 'QUEUED':
        summary.queued += 1;
        break;
      case 'PROCESSING':
        summary.processing += 1;
        break;
      case 'COMPLETED':
        summary.completed += 1;
        break;
      case 'FAILED':
        summary.failed += 1;
        break;
      default:
        break;
    }
  }
  summary.finished = summary.completed + summary.failed;
  summary.active = summary.queued + summary.processing > 0;
  return summary;
}

/** 稳定排序：生成中 > 排队中 > 失败 > 已完成 > 无任务 */
export function sortGenerationQueueRows(
  statuses: QuestionGenStatusResponse[]
): QuestionGenStatusResponse[] {
  return statuses
    .map((status, index) => ({ status, index }))
    .sort((a, b) => {
      const orderDiff = STATUS_ORDER[a.status.questionGenStatus] - STATUS_ORDER[b.status.questionGenStatus];
      return orderDiff !== 0 ? orderDiff : a.index - b.index;
    })
    .map(entry => entry.status);
}

export function shouldPollGenerationQueue(statuses: QuestionGenStatusResponse[]): boolean {
  return statuses.some(
    status => status.questionGenStatus === 'QUEUED' || status.questionGenStatus === 'PROCESSING'
  );
}

import assert from 'node:assert/strict';
import test from 'node:test';
import type { QuestionGenStatusResponse } from '../../api/knowledgebase';
import {
  shouldPollGenerationQueue,
  sortGenerationQueueRows,
  summarizeGenerationQueue,
} from './questionGenerationQueue.ts';

function buildStatus(
  id: number,
  questionGenStatus: QuestionGenStatusResponse['questionGenStatus'],
  extra: Partial<QuestionGenStatusResponse> = {}
): QuestionGenStatusResponse {
  return {
    knowledgeBaseId: id,
    questionGenStatus,
    questionGenTaskId: `task-${id}`,
    questionGenConfig: null,
    savedCount: 0,
    skippedCount: 0,
    message: null,
    error: null,
    updatedAt: '2026-09-05T10:00:00',
    ...extra,
  };
}

test('汇总各状态数量并判断队列是否仍在进行', () => {
  const summary = summarizeGenerationQueue([
    buildStatus(1, 'PROCESSING'),
    buildStatus(2, 'QUEUED'),
    buildStatus(3, 'COMPLETED'),
    buildStatus(4, 'FAILED'),
    buildStatus(5, 'NONE'),
  ]);

  assert.deepEqual(
    { ...summary },
    {
      total: 5,
      queued: 1,
      processing: 1,
      completed: 1,
      failed: 1,
      finished: 2,
      active: true,
    }
  );
});

test('全部出结果后队列视为结束', () => {
  const summary = summarizeGenerationQueue([
    buildStatus(1, 'COMPLETED'),
    buildStatus(2, 'FAILED'),
  ]);

  assert.equal(summary.active, false);
  assert.equal(summary.finished, 2);
});

test('队列行按生成中、排队、失败、完成稳定排序', () => {
  const rows = sortGenerationQueueRows([
    buildStatus(1, 'COMPLETED'),
    buildStatus(2, 'QUEUED'),
    buildStatus(3, 'PROCESSING'),
    buildStatus(4, 'QUEUED'),
    buildStatus(5, 'FAILED'),
  ]);

  assert.deepEqual(
    rows.map(row => row.knowledgeBaseId),
    [3, 2, 4, 5, 1]
  );
});

test('仅存在排队或生成中任务时继续轮询', () => {
  assert.equal(
    shouldPollGenerationQueue([buildStatus(1, 'QUEUED'), buildStatus(2, 'COMPLETED')]),
    true
  );
  assert.equal(
    shouldPollGenerationQueue([buildStatus(1, 'COMPLETED'), buildStatus(2, 'FAILED')]),
    false
  );
  assert.equal(shouldPollGenerationQueue([]), false);
});

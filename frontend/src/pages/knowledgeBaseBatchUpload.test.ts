import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildBatchQueue,
  collectQueueKeys,
  deriveCategoryFromPath,
  extractFilesFromDataTransfer,
  getFileExtension,
  getUploadRetryDelay,
  getUploadThrottleWait,
  isRateLimitError,
  removeQueueItems,
  summarizeQueue,
  validateBatchFile,
  KB_MAX_FILE_SIZE,
  KB_UPLOAD_MIN_INTERVAL_MS,
  KB_UPLOAD_RETRY_DELAYS_MS,
} from './knowledgeBaseBatchUpload.ts';

function makeFile(name: string, size = 1024): File {
  return { name, size } as File;
}

test('deriveCategoryFromPath：所选文件夹名即第一级，子文件夹依次为第二、三级', () => {
  assert.equal(deriveCategoryFromPath('题库/MySQL 实战/1.pdf'), '题库/MySQL 实战');
  assert.equal(deriveCategoryFromPath('题库/Redis/a/b.pdf'), '题库/Redis/a');
  assert.equal(deriveCategoryFromPath('AI/agent/agent-basis.md'), 'AI/agent');
});

test('deriveCategoryFromPath：所选文件夹根目录下的文件只取一级', () => {
  assert.equal(deriveCategoryFromPath('题库/1.pdf'), '题库');
  assert.equal(deriveCategoryFromPath('ai/ai-core-concepts.md'), 'ai');
});

test('deriveCategoryFromPath：无目录（仅文件名）返回 null', () => {
  assert.equal(deriveCategoryFromPath('1.pdf'), null);
  assert.equal(deriveCategoryFromPath(null), null);
  assert.equal(deriveCategoryFromPath(''), null);
});

test('getFileExtension：小写扩展名', () => {
  assert.equal(getFileExtension('a.PDF'), '.pdf');
  assert.equal(getFileExtension('a.docx'), '.docx');
  assert.equal(getFileExtension('noext'), '');
});

test('validateBatchFile：合法 PDF 通过', () => {
  assert.equal(validateBatchFile(makeFile('a.pdf')), null);
});

test('validateBatchFile：不合法扩展名被拒绝', () => {
  const error = validateBatchFile(makeFile('a.zip'));
  assert.ok(error?.includes('不支持的文件类型'));
});

test('validateBatchFile：超过 50MB 拒绝', () => {
  const error = validateBatchFile(makeFile('a.pdf', KB_MAX_FILE_SIZE + 1));
  assert.ok(error?.includes('50MB'));
});

test('validateBatchFile：空文件拒绝', () => {
  const error = validateBatchFile(makeFile('a.pdf', 0));
  assert.ok(error?.includes('为空'));
});

test('buildBatchQueue：推导分类并标记不合法文件', () => {
  const queue = buildBatchQueue([
    { file: makeFile('a.pdf'), relativePath: '题库/MySQL/a.pdf' },
    { file: makeFile('b.pdf'), relativePath: '题库/Redis/基础/b.pdf' },
    { file: makeFile('c.zip'), relativePath: null },
  ]);

  assert.equal(queue.length, 3);
  assert.equal(queue[0].status, 'waiting');
  assert.equal(queue[0].category, '题库/MySQL');
  assert.equal(queue[1].status, 'waiting');
  assert.equal(queue[1].category, '题库/Redis/基础');
  assert.equal(queue[2].status, 'invalid');
  assert.ok(queue[2].error?.includes('不支持的文件类型'));
});

test('deriveCategoryFromPath：超过 3 级的深层目录会被截断', () => {
  assert.equal(deriveCategoryFromPath('题库/Redis/基础/进阶/b.pdf'), '题库/Redis/基础');
  assert.equal(deriveCategoryFromPath('AI/agent/rag/advanced/x.md'), 'AI/agent/rag');
});

test('buildBatchQueue：同名同大小的文件批内去重', () => {
  const queue = buildBatchQueue([
    { file: makeFile('a.pdf', 100), relativePath: null },
    { file: makeFile('a.pdf', 100), relativePath: null },
    { file: makeFile('a.pdf', 200), relativePath: null },
  ]);

  assert.equal(queue[0].status, 'waiting');
  assert.equal(queue[1].status, 'invalid');
  assert.ok(queue[1].error?.includes('重复'));
  assert.equal(queue[2].status, 'waiting');
});

test('buildBatchQueue：传入已有队列键时继续去重', () => {
  const existing = buildBatchQueue([{ file: makeFile('a.pdf', 100), relativePath: null }]);
  const appended = buildBatchQueue([{ file: makeFile('a.pdf', 100), relativePath: null }], 2, collectQueueKeys(existing));

  assert.equal(appended[0].status, 'invalid');
  assert.equal(appended[0].id, 2);
});

test('collectQueueKeys：失败与无效项不占用去重键', () => {
  const queue = buildBatchQueue([
    { file: makeFile('a.pdf', 100), relativePath: null },
    { file: makeFile('bad.zip', 100), relativePath: null },
  ]);
  queue[0].status = 'failed';

  const keys = collectQueueKeys(queue);
  assert.equal(keys.has('a.pdf:100'), false);
  assert.equal(keys.size, 0);
});

test('removeQueueItems：不能移除上传中的项', () => {
  const queue = buildBatchQueue([
    { file: makeFile('a.pdf'), relativePath: null },
    { file: makeFile('b.pdf'), relativePath: null },
  ]);
  queue[0].status = 'uploading';

  const next = removeQueueItems(queue, [queue[0].id, queue[1].id]);

  assert.equal(next.length, 1);
  assert.equal(next[0].fileName, 'a.pdf');
});

test('summarizeQueue：统计各状态且全部结束时 finished 为 true', () => {
  const queue = buildBatchQueue([
    { file: makeFile('a.pdf'), relativePath: null },
    { file: makeFile('b.pdf'), relativePath: null },
    { file: makeFile('c.zip'), relativePath: null },
  ]);
  queue[0].status = 'queued';
  queue[1].status = 'duplicate';

  const summary = summarizeQueue(queue);
  assert.equal(summary.total, 3);
  assert.equal(summary.queued, 1);
  assert.equal(summary.duplicate, 1);
  assert.equal(summary.invalid, 1);
  assert.equal(summary.finished, true);
  assert.equal(summary.uploading, false);
});

test('summarizeQueue：空队列未完成', () => {
  const summary = summarizeQueue([]);
  assert.equal(summary.finished, false);
});

test('extractFilesFromDataTransfer：兼容仅有 files 的 DataTransfer', () => {
  const file = makeFile('a.pdf');
  const dataTransfer = {
    items: [],
    files: [file],
  } as unknown as DataTransfer;

  const { files, hasFolder } = extractFilesFromDataTransfer(dataTransfer);
  assert.equal(hasFolder, false);
  assert.equal(files.length, 1);
  assert.equal(files[0].file.name, 'a.pdf');
  assert.equal(files[0].relativePath, null);
});

test('isRateLimitError：识别服务端限流文案，不误伤普通错误', () => {
  assert.equal(isRateLimitError(new Error('请求过于频繁，请稍后再试')), true);
  assert.equal(isRateLimitError('请求过于频繁'), true);
  assert.equal(isRateLimitError(new Error('不支持的文件类型 .zip')), false);
  assert.equal(isRateLimitError(new Error('上传失败，可能是网络超时或连接中断，请重试')), false);
  assert.equal(isRateLimitError(null), false);
  assert.equal(isRateLimitError(undefined), false);
});

test('getUploadRetryDelay：退避等待递增，序列耗尽返回 null', () => {
  assert.equal(getUploadRetryDelay(0), KB_UPLOAD_RETRY_DELAYS_MS[0]);
  assert.equal(getUploadRetryDelay(1), KB_UPLOAD_RETRY_DELAYS_MS[1]);
  assert.ok(getUploadRetryDelay(1)! > getUploadRetryDelay(0)!);
  assert.equal(getUploadRetryDelay(KB_UPLOAD_RETRY_DELAYS_MS.length), null);
  assert.equal(getUploadRetryDelay(-1), null);
});

test('getUploadThrottleWait：两次请求之间补足最小间隔', () => {
  assert.equal(getUploadThrottleWait(0, 10_000), 0);
  assert.equal(getUploadThrottleWait(10_000, 10_000), KB_UPLOAD_MIN_INTERVAL_MS);
  assert.equal(getUploadThrottleWait(10_000, 10_000 + KB_UPLOAD_MIN_INTERVAL_MS), 0);
  assert.equal(getUploadThrottleWait(10_000, 10_000 + KB_UPLOAD_MIN_INTERVAL_MS - 50), 50);
});

import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildBatchQueue,
  collectQueueKeys,
  deriveCategoryFromPath,
  extractFilesFromDataTransfer,
  getFileExtension,
  removeQueueItems,
  summarizeQueue,
  validateBatchFile,
  KB_MAX_FILE_SIZE,
} from './knowledgeBaseBatchUpload.ts';

function makeFile(name: string, size = 1024): File {
  return { name, size } as File;
}

test('deriveCategoryFromPath：取第一级子文件夹名作为分类', () => {
  assert.equal(deriveCategoryFromPath('题库/MySQL 实战/1.pdf'), 'MySQL 实战');
  assert.equal(deriveCategoryFromPath('题库/Redis/a/b.pdf'), 'Redis');
});

test('deriveCategoryFromPath：根目录文件返回 null', () => {
  assert.equal(deriveCategoryFromPath('题库/1.pdf'), null);
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
    { file: makeFile('b.zip'), relativePath: null },
  ]);

  assert.equal(queue.length, 2);
  assert.equal(queue[0].status, 'waiting');
  assert.equal(queue[0].category, 'MySQL');
  assert.equal(queue[1].status, 'invalid');
  assert.ok(queue[1].error?.includes('不支持的文件类型'));
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

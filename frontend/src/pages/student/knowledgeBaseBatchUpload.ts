// 知识库批量上传纯逻辑：文件校验、文件夹分类推导、上传队列状态流转
// 保持无副作用，便于 node --test 单测

export const KB_SUPPORTED_EXTENSIONS = ['.pdf', '.doc', '.docx', '.txt', '.md'] as const;
export const KB_MAX_FILE_SIZE = 50 * 1024 * 1024;

// 队列项状态：waiting 等待上传 / uploading 上传中 / queued 已入队解析 / duplicate 重复跳过 / failed 上传失败 / invalid 预校验不通过
export type BatchQueueItemStatus =
  | 'waiting'
  | 'uploading'
  | 'queued'
  | 'duplicate'
  | 'failed'
  | 'invalid';

export interface BatchFileInput {
  file: File;
  relativePath: string | null;
}

export interface BatchQueueItem {
  id: number;
  file: File;
  relativePath: string | null;
  fileName: string;
  category: string | null;
  status: BatchQueueItemStatus;
  error: string | null;
}

/** 分类层级上限：文件夹路径最多取前 3 段作为分类（一级/二级/三级） */
export const MAX_CATEGORY_DEPTH = 3;

// 从 webkitRelativePath 推导分类：所选文件夹名即第一级分类，其下子文件夹依次为第二、三级，
// 拼成 "一级/二级/三级"（如 ai/agent/rag）；最后一段是文件名，不计入分类，
// 超过 MAX_CATEGORY_DEPTH 的更深层级会被截断。仅含文件名的路径返回 null，交由默认分类兜底
export function deriveCategoryFromPath(relativePath: string | null | undefined): string | null {
  if (!relativePath) return null;
  const segments = relativePath.split('/').filter(Boolean);
  // 去掉最后一段（文件名），剩下的就是目录层级
  const dirs = segments.slice(0, -1);
  if (dirs.length === 0) return null;
  return dirs.slice(0, MAX_CATEGORY_DEPTH).join('/');
}

export function getFileExtension(fileName: string): string {
  const idx = fileName.lastIndexOf('.');
  return idx > 0 ? fileName.slice(idx).toLowerCase() : '';
}

// 客户端预校验，返回错误原因或 null
export function validateBatchFile(file: File): string | null {
  const ext = getFileExtension(file.name);
  if (!(KB_SUPPORTED_EXTENSIONS as readonly string[]).includes(ext)) {
    return `不支持的文件类型 ${ext || '（无扩展名）'}，仅支持 PDF、DOCX、DOC、TXT、MD`;
  }
  if (file.size > KB_MAX_FILE_SIZE) {
    return '文件大小超过 50MB 限制';
  }
  if (file.size === 0) {
    return '文件内容为空';
  }
  return null;
}

function queueKey(fileName: string, size: number): string {
  return `${fileName}:${size}`;
}

// 已占用去重键的队列项（失败/无效项不占用，允许修正后重新加入）
export function collectQueueKeys(items: BatchQueueItem[]): Set<string> {
  const keys = new Set<string>();
  for (const item of items) {
    if (item.status !== 'invalid' && item.status !== 'failed') {
      keys.add(queueKey(item.fileName, item.file.size));
    }
  }
  return keys;
}

// 构建上传队列：预校验 + 批内去重（同名同大小视为重复）
export function buildBatchQueue(
  inputs: BatchFileInput[],
  startId = 1,
  existingKeys?: Set<string>
): BatchQueueItem[] {
  const seen = existingKeys ? new Set(existingKeys) : new Set<string>();
  let nextId = startId;
  const items: BatchQueueItem[] = [];
  for (const { file, relativePath } of inputs) {
    const error = validateBatchFile(file);
    const key = queueKey(file.name, file.size);
    const isDuplicate = seen.has(key);
    if (!error && !isDuplicate) {
      seen.add(key);
    }
    items.push({
      id: nextId++,
      file,
      relativePath,
      fileName: file.name,
      category: deriveCategoryFromPath(relativePath),
      status: error ? 'invalid' : isDuplicate ? 'invalid' : 'waiting',
      error: error ?? (isDuplicate ? '与本次已选择的文件重复' : null),
    });
  }
  return items;
}

// 从队列中移除指定项（仅允许移除未在上传中的）
export function removeQueueItems(items: BatchQueueItem[], ids: number[]): BatchQueueItem[] {
  const idSet = new Set(ids);
  return items.filter(item => !(idSet.has(item.id) && item.status !== 'uploading'));
}

export interface BatchQueueSummary {
  total: number;
  waiting: number;
  uploading: boolean;
  queued: number;
  duplicate: number;
  failed: number;
  invalid: number;
  finished: boolean;
}

export function summarizeQueue(items: BatchQueueItem[]): BatchQueueSummary {
  let waiting = 0;
  let uploading = 0;
  let queued = 0;
  let duplicate = 0;
  let failed = 0;
  let invalid = 0;
  for (const item of items) {
    switch (item.status) {
      case 'waiting':
        waiting++;
        break;
      case 'uploading':
        uploading++;
        break;
      case 'queued':
        queued++;
        break;
      case 'duplicate':
        duplicate++;
        break;
      case 'failed':
        failed++;
        break;
      case 'invalid':
        invalid++;
        break;
    }
  }
  return {
    total: items.length,
    waiting,
    uploading: uploading > 0,
    queued,
    duplicate,
    failed,
    invalid,
    finished: items.length > 0 && waiting === 0 && uploading === 0,
  };
}

// ========== 上传节流与限流退避 ==========

// 服务端批次上传接口按 IP 限流 10 次/秒（KnowledgeBaseBatchController），
// 两次请求至少间隔 250ms，保证串行上传稳定留在阈值内
export const KB_UPLOAD_MIN_INTERVAL_MS = 250;

// 命中限流后的退避等待序列（毫秒），序列耗尽仍失败才标记为上传失败
export const KB_UPLOAD_RETRY_DELAYS_MS: readonly number[] = [1000, 2500, 5000];

// 服务端限流文案（RateLimitAspect 固定抛出「请求过于频繁，请稍后再试」）
export const KB_UPLOAD_RATE_LIMIT_HINT = '请求过于频繁';

export function isRateLimitError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : typeof error === 'string' ? error : '';
  return message.includes(KB_UPLOAD_RATE_LIMIT_HINT);
}

// 第 attempt 次重试（从 0 开始）前的等待时长；返回 null 表示重试次数已耗尽
export function getUploadRetryDelay(attempt: number): number | null {
  if (attempt < 0 || attempt >= KB_UPLOAD_RETRY_DELAYS_MS.length) return null;
  return KB_UPLOAD_RETRY_DELAYS_MS[attempt];
}

// 距上次上传发起不足最小间隔时，返回需要补等的毫秒数（0 表示可立即发起）
export function getUploadThrottleWait(lastRequestAt: number, now: number): number {
  return Math.max(0, KB_UPLOAD_MIN_INTERVAL_MS - (now - lastRequestAt));
}

// 提取 DataTransfer 中的文件（文件夹拖拽暂不支持，返回是否检测到文件夹）
export function extractFilesFromDataTransfer(
  dataTransfer: DataTransfer
): { files: BatchFileInput[]; hasFolder: boolean } {
  const files: BatchFileInput[] = [];
  let hasFolder = false;
  for (const item of Array.from(dataTransfer.items)) {
    if (item.kind !== 'file') continue;
    const entry = (item as DataTransferItem & { webkitGetAsEntry?: () => { isDirectory: boolean } | null })
      .webkitGetAsEntry?.();
    if (entry?.isDirectory) {
      hasFolder = true;
      continue;
    }
    const file = item.getAsFile();
    if (file) {
      files.push({ file, relativePath: null });
    }
  }
  if (files.length === 0 && dataTransfer.items.length === 0 && dataTransfer.files.length > 0) {
    for (const file of Array.from(dataTransfer.files)) {
      files.push({ file, relativePath: null });
    }
  }
  return { files, hasFolder };
}

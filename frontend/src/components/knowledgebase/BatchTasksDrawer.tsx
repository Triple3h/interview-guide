import { useCallback, useEffect, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import {
  AlertCircle,
  ArrowLeft,
  CheckCircle2,
  Clock,
  Copy,
  Database,
  FileText,
  Loader2,
  RefreshCw,
  X,
} from 'lucide-react';
import {
  knowledgeBaseApi,
  type KbBatchDetail,
  type KbBatchItem,
  type KbBatchItemStatus,
  type KbBatchSummary,
} from '../../api/knowledgebase';
import { getErrorMessage } from '../../api/request';

interface BatchTasksDrawerProps {
  open: boolean;
  /** 从上传页跳转时自动打开的批次ID */
  initialBatchId: number | null;
  onClose: () => void;
}

const ITEM_STATUS_META: Record<KbBatchItemStatus, { label: string; icon: React.ReactNode; className: string }> = {
  PENDING: {
    label: '排队中',
    icon: <Clock className="w-4 h-4" />,
    className: 'text-amber-600 dark:text-amber-400',
  },
  PROCESSING: {
    label: '解析中',
    icon: <Loader2 className="w-4 h-4 animate-spin" />,
    className: 'text-primary-600 dark:text-primary-400',
  },
  COMPLETED: {
    label: '已完成',
    icon: <CheckCircle2 className="w-4 h-4" />,
    className: 'text-green-600 dark:text-green-400',
  },
  FAILED: {
    label: '失败',
    icon: <AlertCircle className="w-4 h-4" />,
    className: 'text-red-500 dark:text-red-400',
  },
  DUPLICATE_SKIPPED: {
    label: '重复跳过',
    icon: <Copy className="w-4 h-4" />,
    className: 'text-slate-500 dark:text-slate-400',
  },
  REJECTED: {
    label: '已拒绝',
    icon: <AlertCircle className="w-4 h-4" />,
    className: 'text-red-400 dark:text-red-500',
  },
};

function formatFileSize(bytes?: number | null): string {
  if (!bytes) return '-';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function BatchStatusBadge({ status }: { status: KbBatchSummary['status'] }) {
  if (status === 'PROCESSING') {
    return (
      <span className="flex items-center gap-1.5 text-xs font-medium text-primary-600 dark:text-primary-400">
        <Loader2 className="w-3.5 h-3.5 animate-spin" />
        进行中
      </span>
    );
  }
  return (
    <span className="flex items-center gap-1.5 text-xs font-medium text-green-600 dark:text-green-400">
      <CheckCircle2 className="w-3.5 h-3.5" />
      已结束
    </span>
  );
}

function BatchProgressBar({ batch }: { batch: Pick<KbBatchSummary, 'total' | 'completed' | 'failed' | 'duplicateSkipped' | 'rejected'> }) {
  const done = batch.completed + batch.failed + batch.duplicateSkipped + batch.rejected;
  const percent = batch.total > 0 ? Math.round((done / batch.total) * 100) : 0;
  return (
    <div className="flex items-center gap-3">
      <div className="flex-1 h-1.5 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
        <div
          className="h-full bg-gradient-to-r from-primary-500 to-primary-600 rounded-full transition-all duration-500"
          style={{ width: `${percent}%` }}
        />
      </div>
      <span className="text-xs text-slate-400 dark:text-slate-500 whitespace-nowrap">
        {done}/{batch.total}
      </span>
    </div>
  );
}

export default function BatchTasksDrawer({ open, initialBatchId, onClose }: BatchTasksDrawerProps) {
  const [view, setView] = useState<'list' | 'detail'>('list');
  const [batches, setBatches] = useState<KbBatchSummary[]>([]);
  const [detail, setDetail] = useState<KbBatchDetail | null>(null);
  const [detailBatchId, setDetailBatchId] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [retryingItemId, setRetryingItemId] = useState<number | null>(null);

  const loadList = useCallback(async () => {
    const data = await knowledgeBaseApi.listUploadBatches(20);
    setBatches(data);
    return data;
  }, []);

  const loadDetail = useCallback(async (batchId: number) => {
    const data = await knowledgeBaseApi.getUploadBatch(batchId);
    setDetail(data);
    return data;
  }, []);

  // 打开时初始化视图：指定批次则直达详情
  useEffect(() => {
    if (!open) return;
    setError('');
    if (initialBatchId != null) {
      setView('detail');
      setDetailBatchId(initialBatchId);
      setLoading(true);
      loadDetail(initialBatchId)
        .catch(err => setError(getErrorMessage(err)))
        .finally(() => setLoading(false));
    } else {
      setView('list');
      setDetailBatchId(null);
      setDetail(null);
      setLoading(true);
      loadList()
        .catch(err => setError(getErrorMessage(err)))
        .finally(() => setLoading(false));
    }
  }, [open, initialBatchId, loadList, loadDetail]);

  // 轮询：列表存在进行中批次 / 详情批次进行中时递归刷新
  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const poll = async () => {
      try {
        if (view === 'list') {
          const data = await loadList();
          if (cancelled) return;
          setError('');
          if (data.some(batch => batch.status === 'PROCESSING')) {
            timer = setTimeout(poll, 5000);
          }
        } else if (detailBatchId != null) {
          const data = await loadDetail(detailBatchId);
          if (cancelled) return;
          setError('');
          if (data.status === 'PROCESSING') {
            timer = setTimeout(poll, 3000);
          }
        }
      } catch (err) {
        if (!cancelled) {
          timer = setTimeout(poll, 5000);
          console.error('刷新解析进度失败:', err);
        }
      }
    };

    void poll();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [open, view, detailBatchId, loadList, loadDetail]);

  const openDetail = (batchId: number) => {
    setView('detail');
    setDetailBatchId(batchId);
    setDetail(null);
    setLoading(true);
    loadDetail(batchId)
      .then(() => setError(''))
      .catch(err => setError(getErrorMessage(err)))
      .finally(() => setLoading(false));
  };

  const backToList = () => {
    setView('list');
    setDetail(null);
    setDetailBatchId(null);
  };

  const handleRetryItem = async (item: KbBatchItem) => {
    if (item.kbId == null || retryingItemId != null) return;
    setRetryingItemId(item.itemId);
    try {
      await knowledgeBaseApi.revectorize(item.kbId);
      if (detailBatchId != null) {
        await loadDetail(detailBatchId);
      }
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setRetryingItemId(null);
    }
  };

  return (
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-50">
          <motion.button
            type="button"
            aria-label="关闭解析任务"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onClose}
            className="absolute inset-0 bg-black/40"
          />
          <motion.div
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ type: 'tween', duration: 0.25 }}
            className="absolute right-0 top-0 h-full w-full max-w-2xl overflow-y-auto bg-white dark:bg-slate-900 shadow-2xl border-l border-slate-200 dark:border-slate-700"
          >
            {/* 头部 */}
            <div className="sticky top-0 z-10 bg-white dark:bg-slate-900 border-b border-slate-100 dark:border-slate-700 px-5 py-4 flex items-center justify-between">
              <div className="flex items-center gap-3">
                {view === 'detail' && (
                  <button
                    type="button"
                    onClick={backToList}
                    className="p-1.5 rounded-lg text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                    title="返回批次列表"
                  >
                    <ArrowLeft className="w-4 h-4" />
                  </button>
                )}
                <div className="flex items-center gap-3">
                  <div className="p-2 bg-primary-50 dark:bg-primary-900/30 rounded-lg">
                    <Database className="w-5 h-5 text-primary-500" />
                  </div>
                  <div>
                    <h2 className="font-bold text-slate-900 dark:text-white">
                      {view === 'list' ? '解析任务' : detail?.name ?? '批次详情'}
                    </h2>
                    <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                      {view === 'list' ? '每次批量上传作为一个解析任务，服务端串行处理' : '批次内每个文件的解析进度'}
                    </p>
                  </div>
                </div>
              </div>
              <button
                type="button"
                onClick={onClose}
                aria-label="关闭"
                className="p-2 rounded-lg text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            {/* 内容 */}
            <div className="p-5">
              {error && (
                <div className="flex items-center gap-2 text-sm text-red-500 bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 rounded-lg px-3 py-2 mb-4">
                  <AlertCircle className="w-4 h-4 shrink-0" />
                  {error}
                </div>
              )}

              {loading ? (
                <div className="flex items-center justify-center py-16">
                  <Loader2 className="w-7 h-7 text-primary-500 animate-spin" />
                </div>
              ) : view === 'list' ? (
                batches.length === 0 ? (
                  <div className="text-center py-16">
                    <Database className="w-12 h-12 text-slate-300 dark:text-slate-600 mx-auto mb-3" />
                    <p className="text-sm text-slate-500 dark:text-slate-400">暂无解析任务，去上传页批量导入文件吧</p>
                  </div>
                ) : (
                  <ul className="space-y-3">
                    {batches.map(batch => (
                      <li key={batch.batchId}>
                        <button
                          type="button"
                          onClick={() => openDetail(batch.batchId)}
                          className="w-full text-left bg-slate-50 dark:bg-slate-800/60 border border-slate-100 dark:border-slate-700 rounded-xl p-4 hover:border-primary-300 dark:hover:border-primary-700 transition-colors"
                        >
                          <div className="flex items-center justify-between gap-3 mb-2">
                            <div className="min-w-0">
                              <p className="text-sm font-medium text-slate-800 dark:text-white truncate">
                                {batch.name}
                              </p>
                              <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
                                {formatDate(batch.createdAt)} · 共 {batch.total} 个文件
                              </p>
                            </div>
                            <BatchStatusBadge status={batch.status} />
                          </div>
                          <BatchProgressBar batch={batch} />
                          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 mt-2 text-xs text-slate-500 dark:text-slate-400">
                            <span className="text-green-600 dark:text-green-400">完成 {batch.completed}</span>
                            {batch.processing > 0 && (
                              <span className="text-primary-600 dark:text-primary-400">解析中 {batch.processing}</span>
                            )}
                            {batch.pending > 0 && <span>排队 {batch.pending}</span>}
                            {batch.failed > 0 && (
                              <span className="text-red-500 dark:text-red-400">失败 {batch.failed}</span>
                            )}
                            {(batch.duplicateSkipped > 0 || batch.rejected > 0) && (
                              <span>跳过 {batch.duplicateSkipped + batch.rejected}</span>
                            )}
                          </div>
                        </button>
                      </li>
                    ))}
                  </ul>
                )
              ) : detail && (
                <>
                  <div className="bg-slate-50 dark:bg-slate-800/60 border border-slate-100 dark:border-slate-700 rounded-xl p-4 mb-4">
                    <div className="flex items-center justify-between gap-3 mb-2">
                      <span className="text-sm text-slate-600 dark:text-slate-300">
                        共 {detail.total} 个文件 · {formatDate(detail.createdAt)}
                      </span>
                      <BatchStatusBadge status={detail.status} />
                    </div>
                    <BatchProgressBar batch={detail} />
                    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 mt-2 text-xs text-slate-500 dark:text-slate-400">
                      <span className="text-green-600 dark:text-green-400">完成 {detail.completed}</span>
                      {detail.processing > 0 && <span className="text-primary-600 dark:text-primary-400">解析中 {detail.processing}</span>}
                      {detail.pending > 0 && <span>排队 {detail.pending}</span>}
                      {detail.failed > 0 && <span className="text-red-500 dark:text-red-400">失败 {detail.failed}</span>}
                      {detail.duplicateSkipped > 0 && <span>重复 {detail.duplicateSkipped}</span>}
                      {detail.rejected > 0 && <span>拒绝 {detail.rejected}</span>}
                    </div>
                  </div>

                  <ul className="space-y-2">
                    {detail.items.map(item => {
                      const meta = ITEM_STATUS_META[item.status];
                      return (
                        <li
                          key={item.itemId}
                          className="flex items-start gap-3 bg-slate-50 dark:bg-slate-800/60 border border-slate-100 dark:border-slate-700 rounded-xl px-4 py-3"
                        >
                          <FileText className="w-4 h-4 text-slate-400 mt-0.5 shrink-0" />
                          <div className="min-w-0 flex-1">
                            <p className="text-sm text-slate-800 dark:text-white truncate">{item.fileName}</p>
                            <div className="flex flex-wrap items-center gap-2 mt-1 text-xs text-slate-400 dark:text-slate-500">
                              {item.category && (
                                <span className="px-1.5 py-0.5 bg-slate-100 dark:bg-slate-700 rounded">
                                  {item.category}
                                </span>
                              )}
                              <span>{formatFileSize(item.fileSize)}</span>
                              {item.error && (
                                <span className="text-red-400 dark:text-red-500 truncate max-w-full" title={item.error}>
                                  {item.error}
                                </span>
                              )}
                            </div>
                          </div>
                          <div className="flex items-center gap-2 shrink-0">
                            {item.status === 'FAILED' && item.kbId != null && (
                              <button
                                type="button"
                                onClick={() => handleRetryItem(item)}
                                disabled={retryingItemId === item.itemId}
                                className="flex items-center gap-1 px-2 py-1 text-xs text-primary-600 dark:text-primary-400 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded transition-colors disabled:opacity-50"
                                title="重新解析"
                              >
                                <RefreshCw className={`w-3.5 h-3.5 ${retryingItemId === item.itemId ? 'animate-spin' : ''}`} />
                                重试
                              </button>
                            )}
                            <span className={`flex items-center gap-1.5 text-xs font-medium whitespace-nowrap ${meta.className}`}>
                              {meta.icon}
                              {meta.label}
                            </span>
                          </div>
                        </li>
                      );
                    })}
                  </ul>
                </>
              )}
            </div>
          </motion.div>
        </div>
      )}
    </AnimatePresence>
  );
}

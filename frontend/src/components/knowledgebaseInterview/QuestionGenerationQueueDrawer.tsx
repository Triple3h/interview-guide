import { useCallback, useEffect, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import {
  AlertCircle,
  CheckCircle2,
  Clock,
  Layers,
  Loader2,
  X,
} from 'lucide-react';
import {
  knowledgeBaseApi,
  type QuestionGenStatus,
  type QuestionGenStatusResponse,
} from '../../api/knowledgebase';
import { getErrorMessage } from '../../api/request';
import { getDifficultyLabel } from '../../constants/knowledgebaseInterview';
import {
  shouldPollGenerationQueue,
  sortGenerationQueueRows,
  summarizeGenerationQueue,
} from './questionGenerationQueue';

interface QuestionGenerationQueueDrawerProps {
  open: boolean;
  /** 本轮批量提交成功的知识库ID列表 */
  knowledgeBaseIds: number[];
  nameById: Map<number, string>;
  onClose: () => void;
}

const STATUS_META: Record<QuestionGenStatus, { label: string; icon: React.ReactNode; className: string }> = {
  QUEUED: {
    label: '排队中',
    icon: <Clock className="w-4 h-4" />,
    className: 'text-amber-600 dark:text-amber-400',
  },
  PROCESSING: {
    label: '生成中',
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
  NONE: {
    label: '无任务',
    icon: <Clock className="w-4 h-4" />,
    className: 'text-slate-400 dark:text-slate-500',
  },
};

function formatUpdatedAt(dateStr: string | null): string {
  if (!dateStr) return '';
  return new Date(dateStr).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

function rowDetail(status: QuestionGenStatusResponse): string | null {
  if (status.questionGenStatus === 'COMPLETED') {
    return status.message || `已生成 ${status.savedCount} 道题，跳过 ${status.skippedCount} 道题`;
  }
  if (status.questionGenStatus === 'FAILED') {
    return status.error || '题目生成失败，请稍后重试';
  }
  const config = status.questionGenConfig;
  if (config) {
    return `${getDifficultyLabel(config.difficulty)} · ${config.questionCount} 题 · 每题追问 ${config.followUpCount} 个`;
  }
  return null;
}

export default function QuestionGenerationQueueDrawer({
  open,
  knowledgeBaseIds,
  nameById,
  onClose,
}: QuestionGenerationQueueDrawerProps) {
  const [statuses, setStatuses] = useState<QuestionGenStatusResponse[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const loadStatuses = useCallback(async (silent = false) => {
    if (knowledgeBaseIds.length === 0) return;
    if (!silent) {
      setLoading(true);
    }
    try {
      const data = await knowledgeBaseApi.batchQuestionGenerationStatus(knowledgeBaseIds);
      setStatuses(sortGenerationQueueRows(data));
      setError('');
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      if (!silent) {
        setLoading(false);
      }
    }
  }, [knowledgeBaseIds]);

  useEffect(() => {
    if (!open) return;
    void loadStatuses();
  }, [open, loadStatuses]);

  const active = shouldPollGenerationQueue(statuses);
  useEffect(() => {
    if (!open || !active) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      await loadStatuses(true);
      if (!cancelled) {
        timer = setTimeout(poll, 3000);
      }
    };
    timer = setTimeout(poll, 3000);
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [open, active, loadStatuses]);

  const summary = summarizeGenerationQueue(statuses);
  const percent = summary.total > 0
    ? Math.round((summary.finished / summary.total) * 100)
    : 0;

  return (
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-50">
          <motion.button
            type="button"
            aria-label="关闭生成队列"
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
            className="absolute right-0 top-0 h-full w-full max-w-xl overflow-y-auto bg-white dark:bg-slate-900 shadow-2xl border-l border-slate-200 dark:border-slate-700"
          >
            {/* 头部 */}
            <div className="sticky top-0 z-10 bg-white dark:bg-slate-900 border-b border-slate-100 dark:border-slate-700 px-5 py-4">
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-3">
                  <div className="p-2 bg-primary-50 dark:bg-primary-900/30 rounded-lg">
                    <Layers className="w-5 h-5 text-primary-500" />
                  </div>
                  <div>
                    <h2 className="font-bold text-slate-900 dark:text-white">题目生成队列</h2>
                    <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                      共 {summary.total} 个任务 · 服务端按队列逐库生成
                      {active && ' · 运行中每 3 秒自动刷新'}
                    </p>
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

              {/* 汇总进度 */}
              {summary.total > 0 && (
                <div className="mt-3">
                  <div className="flex items-center gap-3">
                    <div className="flex-1 h-1.5 bg-slate-100 dark:bg-slate-700 rounded-full overflow-hidden">
                      <div
                        className="h-full bg-gradient-to-r from-primary-500 to-primary-600 rounded-full transition-all duration-500"
                        style={{ width: `${percent}%` }}
                      />
                    </div>
                    <span className="text-xs text-slate-400 dark:text-slate-500 whitespace-nowrap">
                      {summary.finished}/{summary.total}
                    </span>
                  </div>
                  <div className="flex flex-wrap items-center gap-x-4 gap-y-1 mt-2 text-xs text-slate-500 dark:text-slate-400">
                    {summary.processing > 0 && (
                      <span className="text-primary-600 dark:text-primary-400">生成中 {summary.processing}</span>
                    )}
                    {summary.queued > 0 && (
                      <span className="text-amber-600 dark:text-amber-400">排队中 {summary.queued}</span>
                    )}
                    <span className="text-green-600 dark:text-green-400">完成 {summary.completed}</span>
                    {summary.failed > 0 && (
                      <span className="text-red-500 dark:text-red-400">失败 {summary.failed}</span>
                    )}
                  </div>
                </div>
              )}
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
              ) : statuses.length === 0 ? (
                <div className="text-center py-16">
                  <Layers className="w-12 h-12 text-slate-300 dark:text-slate-600 mx-auto mb-3" />
                  <p className="text-sm text-slate-500 dark:text-slate-400">暂无生成任务</p>
                </div>
              ) : (
                <ul className="space-y-2">
                  {statuses.map(status => {
                    const meta = STATUS_META[status.questionGenStatus] ?? STATUS_META.NONE;
                    const detail = rowDetail(status);
                    return (
                      <li
                        key={status.knowledgeBaseId}
                        className="bg-slate-50 dark:bg-slate-800/60 border border-slate-100 dark:border-slate-700 rounded-xl px-4 py-3"
                      >
                        <div className="flex items-center justify-between gap-3">
                          <p className="text-sm font-medium text-slate-800 dark:text-white truncate min-w-0">
                            {nameById.get(status.knowledgeBaseId) || `知识库 ${status.knowledgeBaseId}`}
                          </p>
                          <span className={`flex items-center gap-1.5 text-xs font-medium shrink-0 ${meta.className}`}>
                            {meta.icon}
                            {meta.label}
                          </span>
                        </div>
                        {detail && (
                          <p className={`text-xs mt-1 ${
                            status.questionGenStatus === 'FAILED'
                              ? 'text-red-500 dark:text-red-400'
                              : 'text-slate-500 dark:text-slate-400'
                          }`}>
                            {detail}
                          </p>
                        )}
                        <p className="text-xs text-slate-400 dark:text-slate-500 mt-1">
                          更新于 {formatUpdatedAt(status.updatedAt) || '-'}
                        </p>
                      </li>
                    );
                  })}
                </ul>
              )}
            </div>
          </motion.div>
        </div>
      )}
    </AnimatePresence>
  );
}

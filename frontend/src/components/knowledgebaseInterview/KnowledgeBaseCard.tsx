import { useEffect, useRef, useState } from 'react';
import {
  Check,
  Database,
  Layers,
  Loader2,
  Settings2,
  Sparkles,
} from 'lucide-react';
import {
  knowledgeBaseApi,
  type KnowledgeBaseItem,
  type KnowledgeBaseQuestion,
} from '../../api/knowledgebase';
import { isQuestionGenerationActive } from '../../pages/questionGenerationStatus';

interface KbStats {
  total: number;
  draft: number;
  active: number;
  archived: number;
}

const EMPTY_STATS: KbStats = { total: 0, draft: 0, active: 0, archived: 0 };

function reduceStats(questions: KnowledgeBaseQuestion[]): KbStats {
  return questions.reduce<KbStats>(
    (acc, q) => {
      acc.total += 1;
      if (q.status === 'DRAFT') acc.draft += 1;
      else if (q.status === 'ACTIVE') acc.active += 1;
      else if (q.status === 'ARCHIVED') acc.archived += 1;
      return acc;
    },
    { ...EMPTY_STATS }
  );
}

interface KnowledgeBaseCardProps {
  kb: KnowledgeBaseItem;
  /** 分组视图内的展示名（去掉与分组头重复的分类前缀），缺省用 kb.name */
  displayName?: string;
  /** 分组视图内的展示文件名（同上），缺省用 kb.originalFilename */
  displayFilename?: string;
  selected?: boolean;
  onToggleSelect?: (kb: KnowledgeBaseItem) => void;
  onStart: (kb: KnowledgeBaseItem) => void;
  onGenerate: (kb: KnowledgeBaseItem) => void;
  onManage: (kb: KnowledgeBaseItem) => void;
}

/** 单行布局的知识库条目：勾选 + 名称/文件名 + 统计 + 操作按钮 */
export default function KnowledgeBaseCard({
  kb,
  displayName,
  displayFilename,
  selected = false,
  onToggleSelect,
  onStart,
  onGenerate,
  onManage,
}: KnowledgeBaseCardProps) {
  const ref = useRef<HTMLDivElement>(null);
  const [stats, setStats] = useState<KbStats | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;

    let cancelled = false;
    const fetchStats = () => {
      setLoading(true);
      knowledgeBaseApi
        .listQuestions(kb.id)
        .then(list => {
          if (!cancelled) setStats(reduceStats(list));
        })
        .catch(() => {
          if (!cancelled) setStats({ ...EMPTY_STATS });
        })
        .finally(() => {
          if (!cancelled) setLoading(false);
        });
    };

    if (typeof IntersectionObserver === 'undefined') {
      fetchStats();
      return () => {
        cancelled = true;
      };
    }

    const observer = new IntersectionObserver(
      entries => {
        if (entries.some(entry => entry.isIntersecting)) {
          observer.disconnect();
          fetchStats();
        }
      },
      { rootMargin: '200px' }
    );
    observer.observe(node);

    return () => {
      cancelled = true;
      observer.disconnect();
    };
  }, [kb.id]);

  const generating = isQuestionGenerationActive(kb.questionGenStatus);
  const startable = !!stats && stats.active > 0;
  // 初次渲染 loading=false 且 stats=null，统计位需要兜底显示
  const statsCell = loading || !stats
    ? { total: '–' as string | number, draft: '–', active: '–', archived: '–' }
    : stats;

  return (
    <div
      ref={ref}
      className={`flex flex-wrap items-center gap-x-4 gap-y-2 bg-white dark:bg-slate-800 border rounded-xl px-4 py-3 shadow-sm hover:shadow-md transition-shadow ${
        selected
          ? 'border-primary-400 dark:border-primary-500 ring-1 ring-primary-400/60'
          : 'border-slate-100 dark:border-slate-700'
      }`}
    >
      {onToggleSelect && (
        <button
          type="button"
          onClick={() => onToggleSelect(kb)}
          aria-label={selected ? `取消选择 ${kb.name}` : `选择 ${kb.name}`}
          className={`w-5 h-5 rounded border flex items-center justify-center shrink-0 transition-colors ${
            selected
              ? 'bg-primary-500 border-primary-500 text-white'
              : 'border-slate-300 dark:border-slate-600 text-transparent hover:border-primary-400'
          }`}
        >
          <Check className="w-3.5 h-3.5" />
        </button>
      )}

      <div className="w-8 h-8 rounded-lg bg-primary-50 dark:bg-primary-900/30 text-primary-500 flex items-center justify-center shrink-0">
        <Database className="w-4 h-4" />
      </div>

      <div className="min-w-0 flex-1 basis-48">
        <div className="flex items-center gap-2">
          <h3 className="font-semibold text-sm text-slate-900 dark:text-white truncate" title={kb.name}>
            {displayName ?? kb.name}
          </h3>
          <span className={`shrink-0 px-1.5 py-0.5 rounded text-[11px] font-medium ${
            generating
              ? 'bg-primary-50 dark:bg-primary-900/20 text-primary-600 dark:text-primary-400'
              : startable
              ? 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600 dark:text-emerald-400'
              : 'bg-slate-100 dark:bg-slate-700 text-slate-400'
          }`}>
            {generating
              ? kb.questionGenStatus === 'QUEUED' ? '等待生成' : '生成中'
              : loading ? '统计中' : startable ? '可面试' : '未启用'}
          </span>
        </div>
        <p className="text-xs text-slate-400 truncate mt-0.5" title={kb.originalFilename}>
          {displayFilename ?? kb.originalFilename}
        </p>
      </div>

      <div className="flex items-center gap-4 shrink-0">
        <StatCell label="总数" value={statsCell.total} />
        <StatCell label="草稿" value={statsCell.draft} />
        <StatCell label="已启用" value={statsCell.active} highlight={startable} />
        <StatCell label="已归档" value={statsCell.archived} />
      </div>

      <div className="flex items-center gap-2 shrink-0 ml-auto">
        <button
          type="button"
          onClick={() => onGenerate(kb)}
          disabled={generating}
          className="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 text-xs font-medium hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-40 disabled:cursor-not-allowed whitespace-nowrap"
        >
          {generating ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Sparkles className="w-3.5 h-3.5" />}
          {generating ? '生成中' : '生成题目'}
        </button>
        <button
          type="button"
          onClick={() => onStart(kb)}
          disabled={!startable}
          className="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg bg-primary-500 text-white text-xs font-semibold hover:bg-primary-600 disabled:opacity-40 disabled:cursor-not-allowed whitespace-nowrap"
        >
          <Layers className="w-3.5 h-3.5" />
          开始面试
        </button>
        <button
          type="button"
          onClick={() => onManage(kb)}
          className="inline-flex items-center justify-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 text-xs font-medium hover:bg-slate-50 dark:hover:bg-slate-700 whitespace-nowrap"
        >
          <Settings2 className="w-3.5 h-3.5" />
          管理题库
        </button>
      </div>
    </div>
  );
}

function StatCell({
  label,
  value,
  highlight = false,
}: {
  label: string;
  value: string | number;
  highlight?: boolean;
}) {
  return (
    <div className="text-center min-w-8">
      <p className={`text-sm font-bold leading-tight ${highlight ? 'text-primary-600 dark:text-primary-400' : 'text-slate-900 dark:text-white'}`}>
        {value}
      </p>
      <p className="text-[10px] text-slate-400 leading-tight">{label}</p>
    </div>
  );
}

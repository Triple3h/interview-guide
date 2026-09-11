import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ArrowDownUp,
  BookOpen,
  ChevronDown,
  ChevronRight,
  Database,
  Folder,
  FolderTree,
  Layers,
  Loader2,
  Plus,
  RefreshCw,
  Search,
  Sparkles,
  X,
} from 'lucide-react';
import {
  knowledgeBaseApi,
  type KnowledgeBaseBatchGenerateResultItem,
  type KnowledgeBaseItem,
} from '../api/knowledgebase';
import { DEFAULT_DIFFICULTY, DEFAULT_CATEGORY_LIMIT, INPUT_CLASS } from '../constants/knowledgebaseInterview';
import StartKnowledgeBaseInterviewModal, {
  type StartInterviewConfig,
} from '../components/knowledgebaseInterview/StartKnowledgeBaseInterviewModal';
import GenerateKnowledgeBaseQuestionsModal, {
  type GenerateQuestionsConfig,
} from '../components/knowledgebaseInterview/GenerateKnowledgeBaseQuestionsModal';
import Select from '../components/ui/Select';
import CategoryFilterSelect, {
  UNCATEGORIZED_FILTER_VALUE,
} from '../components/knowledgebase/CategoryFilterSelect';
import KnowledgeBaseCard from '../components/knowledgebaseInterview/KnowledgeBaseCard';
import QuestionGenerationQueueDrawer from '../components/knowledgebaseInterview/QuestionGenerationQueueDrawer';
import { isQuestionGenerationActive } from './questionGenerationStatus';

type SortKey = 'time' | 'name' | 'question';

const SORT_OPTIONS: Array<{ value: SortKey; label: string }> = [
  { value: 'time', label: '按时间' },
  { value: 'name', label: '按名称' },
  { value: 'question', label: '按题目数' },
];

/** 未分类在分组与筛选里的显示名 */
const UNCATEGORIZED_LABEL = '未分类';

/** 分类约定「一级/二级」（最多两级），只有第一个斜杠有层级含义，与后端 getCategoryTree 一致 */
function splitCategory(category?: string | null): { parent: string; child: string } {
  const value = (category || '').trim();
  const slash = value.indexOf('/');
  if (slash <= 0) {
    return { parent: value, child: '' };
  }
  return { parent: value.slice(0, slash), child: value.slice(slash + 1) };
}

/** 分类筛选：'' 为全部；'一级' 含其下二级；'一级/二级' 精确匹配；未分类只匹配没有分类的知识库 */
function matchesCategoryFilter(kb: KnowledgeBaseItem, filter: string): boolean {
  if (!filter) return true;
  const { parent, child } = splitCategory(kb.category);
  if (filter === UNCATEGORIZED_FILTER_VALUE) return !parent;
  if (filter.includes('/')) return !!parent && `${parent}/${child}` === filter;
  return parent === filter;
}

interface CategorySubGroup {
  /** 二级分类名，如 agent */
  name: string;
  /** 完整分类值，如 ai/agent，同时用作折叠状态 key */
  fullName: string;
  kbs: KnowledgeBaseItem[];
}

interface CategoryGroup {
  /** 一级分类名（未分类时为「未分类」），同时用作折叠状态 key */
  parent: string;
  /** 一级分组内全部知识库，即一级「整组」操作的作用域 */
  kbs: KnowledgeBaseItem[];
  /** 直接属于一级、没有二级分类的知识库 */
  directKbs: KnowledgeBaseItem[];
  subGroups: CategorySubGroup[];
}

export default function KnowledgeBaseInterviewLandingPage() {
  const navigate = useNavigate();
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [sortKey, setSortKey] = useState<SortKey>('time');
  // '' 全部 / '一级'（含其下二级）/ '一级/二级' / UNCATEGORIZED_FILTER_VALUE 未分类
  const [selectedCategory, setSelectedCategory] = useState('');
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());

  const [startTargets, setStartTargets] = useState<KnowledgeBaseItem[]>([]);
  const [starting, setStarting] = useState(false);
  const [startError, setStartError] = useState('');

  const [generateTargets, setGenerateTargets] = useState<KnowledgeBaseItem[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [generateError, setGenerateError] = useState('');
  const [batchGenerateResult, setBatchGenerateResult] =
    useState<KnowledgeBaseBatchGenerateResultItem[] | null>(null);
  // 非空时展示本轮批量提交的生成队列抽屉
  const [generationQueueIds, setGenerationQueueIds] = useState<number[] | null>(null);
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set());

  const loadKnowledgeBases = useCallback(async () => {
    setLoading(true);
    try {
      const list = await knowledgeBaseApi.getAllKnowledgeBases(sortKey === 'question' ? 'question' : 'time', 'COMPLETED');
      setKnowledgeBases(list);
    } finally {
      setLoading(false);
    }
  }, [sortKey]);

  useEffect(() => {
    loadKnowledgeBases();
  }, [loadKnowledgeBases]);

  const hasActiveGeneration = knowledgeBases.some(kb =>
    isQuestionGenerationActive(kb.questionGenStatus)
  );

  useEffect(() => {
    if (!hasActiveGeneration) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const poll = async () => {
      try {
        const list = await knowledgeBaseApi.getAllKnowledgeBases(
          sortKey === 'question' ? 'question' : 'time',
          'COMPLETED'
        );
        if (!cancelled) setKnowledgeBases(list);
      } finally {
        if (!cancelled) timer = setTimeout(poll, 5000);
      }
    };

    timer = setTimeout(poll, 5000);
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [hasActiveGeneration, sortKey]);

  // 分类树从当前已加载列表反推（只含已完成知识库），保证筛选项都有结果
  const categoryTree = useMemo(() => {
    const childrenByParent = new Map<string, Set<string>>();
    for (const kb of knowledgeBases) {
      const { parent, child } = splitCategory(kb.category);
      if (!parent) continue;
      const children = childrenByParent.get(parent) ?? new Set<string>();
      if (child) children.add(child);
      childrenByParent.set(parent, children);
    }
    return [...childrenByParent.entries()]
      .sort(([a], [b]) => a.localeCompare(b, 'zh'))
      .map(([name, children]) => ({
        name,
        children: [...children].sort((a, b) => a.localeCompare(b, 'zh')),
      }));
  }, [knowledgeBases]);

  const filteredAndSorted = useMemo(() => {
    const trimmed = keyword.trim().toLowerCase();
    const list = knowledgeBases.filter(kb => {
      const matchesKeyword = !trimmed
        || kb.name.toLowerCase().includes(trimmed)
        || (kb.originalFilename || '').toLowerCase().includes(trimmed);
      return matchesKeyword && matchesCategoryFilter(kb, selectedCategory);
    });

    switch (sortKey) {
      case 'name':
        list.sort((a, b) => a.name.localeCompare(b.name, 'zh'));
        break;
      case 'question':
        list.sort((a, b) => b.questionCount - a.questionCount);
        break;
      case 'time':
      default:
        list.sort((a, b) => (b.uploadedAt || '').localeCompare(a.uploadedAt || ''));
        break;
    }
    return list;
  }, [knowledgeBases, keyword, sortKey, selectedCategory]);

  const selectedKbs = useMemo(
    () => knowledgeBases.filter(kb => selectedIds.has(kb.id)),
    [knowledgeBases, selectedIds]
  );
  const knowledgeBaseNameById = useMemo(
    () => new Map(knowledgeBases.map(kb => [kb.id, kb.name])),
    [knowledgeBases]
  );

  // 生成队列常驻入口：展示所有有任务记录的知识库（进行中的排前），有生成任务时计数提示
  const queueKbIds = useMemo(
    () =>
      [...knowledgeBases]
        .filter(kb => kb.questionGenStatus !== 'NONE')
        .sort((a, b) => Number(isQuestionGenerationActive(b.questionGenStatus)) - Number(isQuestionGenerationActive(a.questionGenStatus)))
        .map(kb => kb.id),
    [knowledgeBases]
  );
  const activeGenerationCount = useMemo(
    () => knowledgeBases.filter(kb => isQuestionGenerationActive(kb.questionGenStatus)).length,
    [knowledgeBases]
  );

  // 按一级分类分组，组内再按二级分类分子小节；组内沿用搜索/排序结果，未分类放最后
  const groupedKbs = useMemo<CategoryGroup[]>(() => {
    const groups = new Map<string, {
      kbs: KnowledgeBaseItem[];
      directKbs: KnowledgeBaseItem[];
      subGroups: Map<string, KnowledgeBaseItem[]>;
    }>();

    for (const kb of filteredAndSorted) {
      const { parent, child } = splitCategory(kb.category);
      const key = parent || UNCATEGORIZED_LABEL;
      const group = groups.get(key) ?? {
        kbs: [],
        directKbs: [],
        subGroups: new Map<string, KnowledgeBaseItem[]>(),
      };
      group.kbs.push(kb);
      if (child) {
        const list = group.subGroups.get(child);
        if (list) {
          list.push(kb);
        } else {
          group.subGroups.set(child, [kb]);
        }
      } else {
        group.directKbs.push(kb);
      }
      groups.set(key, group);
    }

    return [...groups.entries()]
      .sort(([a], [b]) => {
        if (a === UNCATEGORIZED_LABEL) return 1;
        if (b === UNCATEGORIZED_LABEL) return -1;
        return a.localeCompare(b, 'zh');
      })
      .map(([parent, group]) => ({
        parent,
        kbs: group.kbs,
        directKbs: group.directKbs,
        subGroups: [...group.subGroups.entries()]
          .sort(([a], [b]) => a.localeCompare(b, 'zh'))
          .map(([name, kbs]) => ({ name, fullName: `${parent}/${name}`, kbs })),
      }));
  }, [filteredAndSorted]);

  const isGroupFullySelected = (kbs: KnowledgeBaseItem[]) =>
    kbs.length > 0 && kbs.every(kb => selectedIds.has(kb.id));

  const toggleGroupCollapse = (category: string) => {
    setCollapsedGroups(prev => {
      const next = new Set(prev);
      if (next.has(category)) {
        next.delete(category);
      } else {
        next.add(category);
      }
      return next;
    });
  };

  const toggleGroupSelection = (kbs: KnowledgeBaseItem[]) => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      if (isGroupFullySelected(kbs)) {
        kbs.forEach(kb => next.delete(kb.id));
      } else {
        kbs.forEach(kb => next.add(kb.id));
      }
      return next;
    });
  };

  const toggleSelect = (kb: KnowledgeBaseItem) => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      if (next.has(kb.id)) {
        next.delete(kb.id);
      } else {
        next.add(kb.id);
      }
      return next;
    });
  };

  const selectAllFiltered = () => {
    setSelectedIds(new Set(filteredAndSorted.map(kb => kb.id)));
  };

  const clearSelection = () => setSelectedIds(new Set());

  // 切换分类筛选时展开命中的一级分组，避免结果被折叠状态挡住
  const handleCategoryChange = (next: string) => {
    setSelectedCategory(next);
    if (!next) return;
    const hitParents = new Set(
      knowledgeBases
        .filter(kb => matchesCategoryFilter(kb, next))
        .map(kb => splitCategory(kb.category).parent || UNCATEGORIZED_LABEL)
    );
    setCollapsedGroups(prev => {
      const updated = new Set(prev);
      hitParents.forEach(parent => updated.delete(parent));
      return updated;
    });
  };

  const handleStart = (kb: KnowledgeBaseItem) => {
    setStartTargets([kb]);
    setStartError('');
  };

  const handleGroupStart = (kbs: KnowledgeBaseItem[]) => {
    if (kbs.length === 0) return;
    setStartTargets(kbs);
    setStartError('');
  };

  const handleBatchStart = () => handleGroupStart(selectedKbs);

  const handleGenerate = (kb: KnowledgeBaseItem) => {
    if (isQuestionGenerationActive(kb.questionGenStatus)) return;
    setGenerateTargets([kb]);
    setGenerateError('');
  };

  const handleGroupGenerate = (kbs: KnowledgeBaseItem[]) => {
    const targets = kbs.filter(kb => !isQuestionGenerationActive(kb.questionGenStatus));
    if (targets.length === 0) return;
    setGenerateTargets(targets);
    setGenerateError('');
  };

  const handleBatchGenerate = () => handleGroupGenerate(selectedKbs);

  const handleManage = (kb: KnowledgeBaseItem) => {
    navigate(`/knowledgebase-interview/${kb.id}/questions`);
  };

  const handleStartSubmit = async (config: StartInterviewConfig) => {
    if (startTargets.length === 0) return;
    setStarting(true);
    setStartError('');
    try {
      const isBatch = startTargets.length > 1;
      const session = isBatch
        ? await knowledgeBaseApi.createBatchInterviewSession({
            knowledgeBaseIds: startTargets.map(kb => kb.id),
            difficulty: config.difficulty,
            mainQuestionCount: config.mainQuestionCount,
            followUpCount: config.followUpCount,
          })
        : await knowledgeBaseApi.createInterviewSession({
            knowledgeBaseId: startTargets[0].id,
            category: config.category.trim() || undefined,
            difficulty: config.difficulty,
            mainQuestionCount: config.mainQuestionCount,
            followUpCount: config.followUpCount,
          });
      const singleKbId = isBatch ? undefined : startTargets[0].id;
      setStartTargets([]);
      clearSelection();
      navigate(`/knowledgebase-interview/${session.sessionId}`, {
        state: singleKbId ? { knowledgeBaseId: singleKbId } : undefined,
      });
    } catch (error) {
      setStartError(error instanceof Error ? error.message : '创建知识库面试失败');
    } finally {
      setStarting(false);
    }
  };

  const handleGenerateSubmit = async (config: GenerateQuestionsConfig) => {
    if (generateTargets.length === 0) return;
    setSubmitting(true);
    setGenerateError('');
    try {
      if (generateTargets.length > 1) {
        const results = await knowledgeBaseApi.generateQuestionsBatch({
          knowledgeBaseIds: generateTargets.map(kb => kb.id),
          difficulty: config.difficulty,
          questionCount: config.questionCount,
          followUpCount: config.followUpCount,
          categoryLimit: config.categoryLimit,
        });
        const submittedIds = results.filter(item => item.submitted).map(item => item.knowledgeBaseId);
        setGenerateTargets([]);
        setBatchGenerateResult(results);
        if (submittedIds.length > 0) {
          setGenerationQueueIds(submittedIds);
        }
        await loadKnowledgeBases();
      } else {
        const result = await knowledgeBaseApi.generateQuestions(generateTargets[0].id, {
          difficulty: config.difficulty,
          questionCount: config.questionCount,
          followUpCount: config.followUpCount,
          categoryLimit: config.categoryLimit,
        });
        const target = generateTargets[0];
        setGenerateTargets([]);
        navigate(`/knowledgebase-interview/${target.id}/questions`, {
          state: {
            highlightStatus: 'ACTIVE',
            questionGenTaskId: result.questionGenTaskId,
          },
        });
      }
    } catch (error) {
      setGenerateError(error instanceof Error ? error.message : '生成失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  const renderKbCard = (kb: KnowledgeBaseItem) => (
    <KnowledgeBaseCard
      key={kb.id}
      kb={kb}
      selected={selectedIds.has(kb.id)}
      onToggleSelect={toggleSelect}
      onStart={handleStart}
      onGenerate={handleGenerate}
      onManage={handleManage}
    />
  );

  return (
    <div className="max-w-[1400px] mx-auto">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white flex items-center gap-3">
            <BookOpen className="w-7 h-7 text-primary-500" />
            知识库面试
          </h1>
          <p className="text-slate-500 dark:text-slate-400 mt-1">
            知识库按分类分组展示，可整组或跨组批量生成题目、开始面试
          </p>
        </div>
        <div className="flex gap-2">
          {queueKbIds.length > 0 && (
            <button
              onClick={() => setGenerationQueueIds(queueKbIds)}
              title="查看题目生成队列"
              className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 text-sm font-medium hover:bg-slate-50 dark:hover:bg-slate-700 whitespace-nowrap"
            >
              {activeGenerationCount > 0 ? (
                <Loader2 className="w-4 h-4 animate-spin text-primary-500" />
              ) : (
                <Layers className="w-4 h-4" />
              )}
              生成队列
              {activeGenerationCount > 0 && (
                <span className="inline-flex items-center justify-center min-w-5 h-5 px-1.5 rounded-full bg-primary-500 text-white text-xs font-semibold">
                  {activeGenerationCount}
                </span>
              )}
            </button>
          )}
          <button
            onClick={loadKnowledgeBases}
            className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 text-sm font-medium hover:bg-slate-50 dark:hover:bg-slate-700 whitespace-nowrap"
          >
            <RefreshCw className="w-4 h-4" />
            刷新
          </button>
          <button
            onClick={() => navigate('/knowledgebase/upload')}
            className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg bg-primary-500 text-white text-sm font-medium hover:bg-primary-600 whitespace-nowrap"
          >
            <Plus className="w-4 h-4" />
            上传知识库
          </button>
        </div>
      </div>

      <div className="bg-white dark:bg-slate-800 border border-slate-100 dark:border-slate-700 rounded-xl p-3 mb-6 flex flex-col sm:flex-row gap-3 sm:items-center">
        <div className="relative flex-1">
          <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            value={keyword}
            onChange={event => setKeyword(event.target.value)}
            className={`${INPUT_CLASS} pl-9`}
            placeholder="按名称或文件名搜索"
          />
        </div>
        <div className="flex items-center gap-2">
          <ArrowDownUp className="w-4 h-4 text-slate-400 shrink-0" />
          <Select
            variant="filter"
            value={sortKey}
            onChange={event => setSortKey(event.target.value as SortKey)}
            className="sm:w-40"
          >
            {SORT_OPTIONS.map(option => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </Select>
        </div>
        <CategoryFilterSelect
          tree={categoryTree}
          value={selectedCategory}
          onChange={handleCategoryChange}
          includeUncategorized
        />
      </div>

      {selectedIds.size > 0 && (
        <div className="mb-4 flex flex-wrap items-center gap-3 rounded-xl border border-primary-200 dark:border-primary-900 bg-primary-50/70 dark:bg-primary-900/20 px-4 py-3">
          <span className="text-sm font-semibold text-primary-700 dark:text-primary-300">
            已选 {selectedIds.size} 个知识库
          </span>
          <button
            type="button"
            onClick={selectAllFiltered}
            className="text-xs font-medium text-slate-500 dark:text-slate-300 hover:text-primary-600 dark:hover:text-primary-400"
          >
            全选当前结果
          </button>
          <button
            type="button"
            onClick={clearSelection}
            className="text-xs font-medium text-slate-500 dark:text-slate-300 hover:text-primary-600 dark:hover:text-primary-400"
          >
            清除选择
          </button>
          <div className="ml-auto flex gap-2">
            <button
              type="button"
              onClick={handleBatchGenerate}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg border border-primary-300 dark:border-primary-700 text-primary-600 dark:text-primary-300 text-xs font-medium hover:bg-white dark:hover:bg-slate-800 whitespace-nowrap"
            >
              <Sparkles className="w-3.5 h-3.5" />
              批量生成题目
            </button>
            <button
              type="button"
              onClick={handleBatchStart}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg bg-primary-500 text-white text-xs font-semibold hover:bg-primary-600 whitespace-nowrap"
            >
              <Layers className="w-3.5 h-3.5" />
              整体开始面试
            </button>
          </div>
        </div>
      )}

      {batchGenerateResult && (
        <div className="mb-6 rounded-xl border border-slate-100 dark:border-slate-700 bg-white dark:bg-slate-800 px-4 py-3 text-sm">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="font-medium text-slate-800 dark:text-slate-100">
                批量生成：已提交 {batchGenerateResult.filter(item => item.submitted).length} / {batchGenerateResult.length} 个任务
              </p>
              {batchGenerateResult.some(item => !item.submitted) && (
                <ul className="mt-1 space-y-0.5 text-xs text-slate-500 dark:text-slate-400">
                  {batchGenerateResult.filter(item => !item.submitted).map(item => (
                    <li key={item.knowledgeBaseId}>
                      {knowledgeBaseNameById.get(item.knowledgeBaseId) || `知识库 ${item.knowledgeBaseId}`}
                      ：{item.message}
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <div className="flex items-center gap-1 shrink-0">
              <button
                type="button"
                onClick={() => {
                  setGenerationQueueIds(
                    batchGenerateResult.filter(item => item.submitted).map(item => item.knowledgeBaseId)
                  );
                }}
                disabled={batchGenerateResult.every(item => !item.submitted)}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400 text-xs font-medium hover:bg-primary-100 dark:hover:bg-primary-900/50 disabled:opacity-50 disabled:cursor-not-allowed whitespace-nowrap"
              >
                <Layers className="w-3.5 h-3.5" />
                查看队列
              </button>
              <button
                type="button"
                onClick={() => setBatchGenerateResult(null)}
                className="p-1.5 rounded-lg text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700"
                aria-label="关闭批量生成结果"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
          </div>
        </div>
      )}

      {loading ? (
        <div className="flex justify-center py-24">
          <Loader2 className="w-8 h-8 animate-spin text-primary-500" />
        </div>
      ) : filteredAndSorted.length === 0 ? (
        <div className="flex flex-col items-center justify-center min-h-[320px] rounded-2xl border border-dashed border-slate-200 dark:border-slate-700 text-slate-400 gap-3">
          <Database className="w-10 h-10" />
          <p className="text-sm">
            {keyword.trim() || selectedCategory
              ? '没有匹配的知识库'
              : '暂无已完成知识库，先上传并等待向量化完成'}
          </p>
        </div>
      ) : (
        <div className="space-y-8">
          {groupedKbs.map(group => {
            const collapsed = collapsedGroups.has(group.parent);
            return (
              <section key={group.parent}>
                <div className="flex flex-wrap items-center gap-3 mb-3">
                  <button
                    type="button"
                    onClick={() => toggleGroupCollapse(group.parent)}
                    className="flex items-center gap-2 text-left group/header min-w-0"
                    aria-expanded={!collapsed}
                  >
                    {collapsed
                      ? <ChevronRight className="w-4 h-4 text-slate-400 shrink-0" />
                      : <ChevronDown className="w-4 h-4 text-slate-400 shrink-0" />}
                    <Folder className="w-5 h-5 text-primary-500 shrink-0" />
                    <h2 className="text-lg font-bold text-slate-900 dark:text-white truncate group-hover/header:text-primary-600 dark:group-hover/header:text-primary-400">
                      {group.parent}
                    </h2>
                  </button>
                  <span className="text-xs text-slate-400 shrink-0">
                    {group.kbs.length} 个知识库{collapsed ? ' · 已收起' : ''}
                  </span>
                  <button
                    type="button"
                    onClick={() => toggleGroupSelection(group.kbs)}
                    className="text-xs font-medium text-slate-400 hover:text-primary-600 dark:hover:text-primary-400 shrink-0"
                  >
                    {isGroupFullySelected(group.kbs) ? '取消全选本组' : '全选本组'}
                  </button>
                  <div className="ml-auto flex gap-2">
                    <button
                      type="button"
                      onClick={() => handleGroupGenerate(group.kbs)}
                      className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 text-xs font-medium hover:bg-slate-50 dark:hover:bg-slate-700 whitespace-nowrap"
                    >
                      <Sparkles className="w-3.5 h-3.5" />
                      整组生成题目
                    </button>
                    <button
                      type="button"
                      onClick={() => handleGroupStart(group.kbs)}
                      className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg bg-primary-500 text-white text-xs font-semibold hover:bg-primary-600 whitespace-nowrap"
                    >
                      <Layers className="w-3.5 h-3.5" />
                      整组开始面试
                    </button>
                  </div>
                </div>
                {!collapsed && (
                  <div className="space-y-4">
                    {/* 直接属于一级、没有二级分类的知识库 */}
                    {group.directKbs.length > 0 && (
                      <div className="space-y-2">
                        {group.directKbs.map(renderKbCard)}
                      </div>
                    )}
                    {/* 二级分类小节：折叠 / 全选 / 整组操作与一级对齐 */}
                    {group.subGroups.map(subGroup => {
                      const subCollapsed = collapsedGroups.has(subGroup.fullName);
                      return (
                        <div
                          key={subGroup.fullName}
                          className="ml-6 border-l border-slate-100 dark:border-slate-700 pl-4"
                        >
                          <div className="flex flex-wrap items-center gap-2 mb-2">
                            <button
                              type="button"
                              onClick={() => toggleGroupCollapse(subGroup.fullName)}
                              className="flex items-center gap-1.5 text-left group/subheader min-w-0"
                              aria-expanded={!subCollapsed}
                            >
                              {subCollapsed
                                ? <ChevronRight className="w-3.5 h-3.5 text-slate-400 shrink-0" />
                                : <ChevronDown className="w-3.5 h-3.5 text-slate-400 shrink-0" />}
                              <FolderTree className="w-4 h-4 text-primary-500 shrink-0" />
                              <h3 className="text-sm font-semibold text-slate-900 dark:text-white truncate group-hover/subheader:text-primary-600 dark:group-hover/subheader:text-primary-400">
                                {subGroup.name}
                              </h3>
                            </button>
                            <span className="text-xs text-slate-400 shrink-0">
                              {subGroup.kbs.length} 个知识库{subCollapsed ? ' · 已收起' : ''}
                            </span>
                            <button
                              type="button"
                              onClick={() => toggleGroupSelection(subGroup.kbs)}
                              className="text-xs font-medium text-slate-400 hover:text-primary-600 dark:hover:text-primary-400 shrink-0"
                            >
                              {isGroupFullySelected(subGroup.kbs) ? '取消全选本组' : '全选本组'}
                            </button>
                            <div className="ml-auto flex gap-2">
                              <button
                                type="button"
                                onClick={() => handleGroupGenerate(subGroup.kbs)}
                                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 text-xs font-medium hover:bg-slate-50 dark:hover:bg-slate-700 whitespace-nowrap"
                              >
                                <Sparkles className="w-3.5 h-3.5" />
                                整组生成题目
                              </button>
                              <button
                                type="button"
                                onClick={() => handleGroupStart(subGroup.kbs)}
                                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-primary-500 text-white text-xs font-semibold hover:bg-primary-600 whitespace-nowrap"
                              >
                                <Layers className="w-3.5 h-3.5" />
                                整组开始面试
                              </button>
                            </div>
                          </div>
                          {!subCollapsed && (
                            <div className="space-y-2">
                              {subGroup.kbs.map(renderKbCard)}
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                )}
              </section>
            );
          })}
        </div>
      )}

      <StartKnowledgeBaseInterviewModal
        open={startTargets.length > 0}
        knowledgeBases={startTargets}
        defaultDifficulty={DEFAULT_DIFFICULTY}
        starting={starting}
        error={startError}
        onClose={() => {
          if (!starting) {
            setStartTargets([]);
            setStartError('');
          }
        }}
        onStart={handleStartSubmit}
      />

      <GenerateKnowledgeBaseQuestionsModal
        open={generateTargets.length > 0}
        knowledgeBaseName={
          generateTargets.length > 1
            ? `${generateTargets.length} 个知识库`
            : (generateTargets[0]?.name || '')
        }
        batch={generateTargets.length > 1}
        defaultDifficulty={DEFAULT_DIFFICULTY}
        defaultCategoryLimit={DEFAULT_CATEGORY_LIMIT}
        submitting={submitting}
        error={generateError}
        onClose={() => {
          if (!submitting) {
            setGenerateTargets([]);
            setGenerateError('');
          }
        }}
        onSubmit={handleGenerateSubmit}
      />

      <QuestionGenerationQueueDrawer
        open={generationQueueIds !== null && generationQueueIds.length > 0}
        knowledgeBaseIds={generationQueueIds ?? []}
        nameById={knowledgeBaseNameById}
        onClose={() => setGenerationQueueIds(null)}
      />
    </div>
  );
}

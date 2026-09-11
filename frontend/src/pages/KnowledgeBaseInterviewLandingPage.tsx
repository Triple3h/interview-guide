import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
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
  type CategoryTreeNode,
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

/** 分类按斜杠分段（一级/二级/三级），去掉空段与首尾空格 */
function categorySegments(category?: string | null): string[] {
  return (category || '')
    .split('/')
    .map(part => part.trim())
    .filter(Boolean);
}

/** 分类筛选：'' 为全部；选中某层即匹配该层及其下全部；未分类只匹配没有分类的知识库 */
function matchesCategoryFilter(kb: KnowledgeBaseItem, filter: string): boolean {
  if (!filter) return true;
  const segments = categorySegments(kb.category);
  if (filter === UNCATEGORIZED_FILTER_VALUE) return segments.length === 0;
  const path = segments.join('/');
  return path === filter || path.startsWith(`${filter}/`);
}

interface CategoryNode {
  /** 本层显示名，如 framework */
  name: string;
  /** 完整路径（未分类时为「未分类」），同时用作折叠状态 key */
  fullPath: string;
  /** 本节点及其下全部知识库，即「整组」操作的作用域 */
  kbs: KnowledgeBaseItem[];
  /** 直接属于本层、没有更深层级的知识库 */
  directKbs: KnowledgeBaseItem[];
  children: CategoryNode[];
}

/** 每层按名称排序（递归） */
function sortCategoryNodes(nodes: CategoryNode[]): void {
  nodes.sort((a, b) => a.name.localeCompare(b.name, 'zh'));
  nodes.forEach(node => sortCategoryNodes(node.children));
}

/**
 * 按分类层级构建分组树：逐级聚合，每层都带上其下全部知识库；
 * 未分类单独成组并固定排在最后
 */
function buildCategoryGroups(kbs: KnowledgeBaseItem[]): CategoryNode[] {
  const roots: CategoryNode[] = [];
  const nodeByPath = new Map<string, CategoryNode>();
  let uncategorized: CategoryNode | null = null;

  for (const kb of kbs) {
    const segments = categorySegments(kb.category);
    if (segments.length === 0) {
      if (!uncategorized) {
        uncategorized = {
          name: UNCATEGORIZED_LABEL,
          fullPath: UNCATEGORIZED_LABEL,
          kbs: [],
          directKbs: [],
          children: [],
        };
      }
      uncategorized.kbs.push(kb);
      uncategorized.directKbs.push(kb);
      continue;
    }

    let parentPath = '';
    let siblings = roots;
    segments.forEach((segment, index) => {
      const path = parentPath ? `${parentPath}/${segment}` : segment;
      let node = nodeByPath.get(path);
      if (!node) {
        node = { name: segment, fullPath: path, kbs: [], directKbs: [], children: [] };
        nodeByPath.set(path, node);
        siblings.push(node);
      }
      node.kbs.push(kb);
      if (index === segments.length - 1) {
        node.directKbs.push(kb);
      }
      siblings = node.children;
      parentPath = path;
    });
  }

  sortCategoryNodes(roots);
  if (uncategorized) {
    roots.push(uncategorized);
  }
  return roots;
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
  const categoryTree = useMemo<CategoryTreeNode[]>(() => {
    const toTree = (nodes: CategoryNode[]): CategoryTreeNode[] =>
      nodes
        .filter(node => node.fullPath !== UNCATEGORIZED_LABEL)
        .map(node => ({ name: node.fullPath, children: toTree(node.children) }));
    return toTree(buildCategoryGroups(knowledgeBases));
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

  // 按分类层级分组（一级 → 二级 → 三级），组内沿用搜索/排序结果，未分类放最后
  const groupedKbs = useMemo<CategoryNode[]>(
    () => buildCategoryGroups(filteredAndSorted),
    [filteredAndSorted]
  );

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

  // 切换分类筛选时展开命中路径上的所有层级，避免结果被折叠状态挡住
  const handleCategoryChange = (next: string) => {
    setSelectedCategory(next);
    if (!next) return;
    setCollapsedGroups(prev => {
      const updated = new Set(prev);
      const paths = next === UNCATEGORIZED_FILTER_VALUE
        ? [UNCATEGORIZED_LABEL]
        : categorySegments(next).map((_, index, all) => all.slice(0, index + 1).join('/'));
      paths.forEach(path => updated.delete(path));
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

  /**
   * 递归渲染分类分组：一级用大标题，更深层逐级缩进并缩小字号，
   * 每层都带折叠 / 全选本组 / 整组生成题目 / 整组开始面试
   */
  const renderCategoryNode = (node: CategoryNode, depth: number): ReactNode => {
    const isRoot = depth === 0;
    const collapsed = collapsedGroups.has(node.fullPath);
    const Heading = isRoot ? 'h2' : 'h3';
    return (
      <div
        key={node.fullPath}
        className={
          isRoot
            ? 'space-y-4'
            : 'ml-5 border-l border-slate-100 dark:border-slate-700 pl-4 space-y-4'
        }
      >
        <div className={`flex flex-wrap items-center ${isRoot ? 'gap-3' : 'gap-2'}`}>
          <button
            type="button"
            onClick={() => toggleGroupCollapse(node.fullPath)}
            className={`flex items-center text-left min-w-0 ${
              isRoot ? 'gap-2 group/header' : 'gap-1.5 group/subheader'
            }`}
            aria-expanded={!collapsed}
          >
            {collapsed
              ? <ChevronRight className={`${isRoot ? 'w-4 h-4' : 'w-3.5 h-3.5'} text-slate-400 shrink-0`} />
              : <ChevronDown className={`${isRoot ? 'w-4 h-4' : 'w-3.5 h-3.5'} text-slate-400 shrink-0`} />}
            {isRoot
              ? <Folder className="w-5 h-5 text-primary-500 shrink-0" />
              : <FolderTree className={`${depth === 1 ? 'w-4 h-4' : 'w-3.5 h-3.5'} text-primary-500 shrink-0`} />}
            <Heading
              className={`truncate ${
                isRoot
                  ? 'text-lg font-bold text-slate-900 dark:text-white group-hover/header:text-primary-600 dark:group-hover/header:text-primary-400'
                  : `text-sm ${depth === 1 ? 'font-semibold' : 'font-medium'} text-slate-900 dark:text-white group-hover/subheader:text-primary-600 dark:group-hover/subheader:text-primary-400`
              }`}
            >
              {node.name}
            </Heading>
          </button>
          <span className="text-xs text-slate-400 shrink-0">
            {node.kbs.length} 个知识库{collapsed ? ' · 已收起' : ''}
          </span>
          <button
            type="button"
            onClick={() => toggleGroupSelection(node.kbs)}
            className="text-xs font-medium text-slate-400 hover:text-primary-600 dark:hover:text-primary-400 shrink-0"
          >
            {isGroupFullySelected(node.kbs) ? '取消全选本组' : '全选本组'}
          </button>
          <div className="ml-auto flex gap-2">
            <button
              type="button"
              onClick={() => handleGroupGenerate(node.kbs)}
              className={`inline-flex items-center gap-1.5 px-3 ${isRoot ? 'py-2' : 'py-1.5'} rounded-lg border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 text-xs font-medium hover:bg-slate-50 dark:hover:bg-slate-700 whitespace-nowrap`}
            >
              <Sparkles className="w-3.5 h-3.5" />
              整组生成题目
            </button>
            <button
              type="button"
              onClick={() => handleGroupStart(node.kbs)}
              className={`inline-flex items-center gap-1.5 px-3 ${isRoot ? 'py-2' : 'py-1.5'} rounded-lg bg-primary-500 text-white text-xs font-semibold hover:bg-primary-600 whitespace-nowrap`}
            >
              <Layers className="w-3.5 h-3.5" />
              整组开始面试
            </button>
          </div>
        </div>
        {!collapsed && (
          <>
            {/* 直接属于本层、没有更深分类的知识库 */}
            {node.directKbs.length > 0 && (
              <div className="space-y-2">
                {node.directKbs.map(renderKbCard)}
              </div>
            )}
            {node.children.map(child => renderCategoryNode(child, depth + 1))}
          </>
        )}
      </div>
    );
  };

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
          {groupedKbs.map(node => renderCategoryNode(node, 0))}
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

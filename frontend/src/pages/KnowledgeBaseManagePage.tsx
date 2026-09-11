import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useLocation} from 'react-router-dom';
import {AnimatePresence, motion} from 'framer-motion';
import {
  AlertCircle,
  Check,
  CheckCircle,
  Clock,
  Database,
  Download,
  Edit3,
  Eye,
  FileText,
  FolderTree,
  HardDrive,
  ListChecks,
  Loader2,
  MessageSquare,
  RefreshCw,
  Search,
  Trash2,
  Upload,
  X,
} from 'lucide-react';
import {knowledgeBaseApi, CategoryTreeNode, KnowledgeBaseItem, KnowledgeBasePage, KnowledgeBaseStats, KbBatchSummary, SortOption, VectorStatus,} from '../api/knowledgebase';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import BatchTasksDrawer from '../components/knowledgebase/BatchTasksDrawer';
import BatchCategoryModal from '../components/knowledgebase/BatchCategoryModal';
import CategoryFilterSelect from '../components/knowledgebase/CategoryFilterSelect';
import Select from '../components/ui/Select';
import Pagination from '../components/ui/Pagination';
import { stripCategoryPrefix } from '../utils/knowledgeBase';
// 复用批量上传的限流识别与退避序列（服务端限流文案与重试节奏全站一致）
import { getUploadRetryDelay, isRateLimitError } from './knowledgeBaseBatchUpload';

interface KnowledgeBaseManagePageProps {
  onUpload: () => void;
  onChat: () => void;
}

// 格式化文件大小
function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

// 格式化日期
function formatDate(dateStr: string): string {
  const date = new Date(dateStr);
  return date.toLocaleDateString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  });
}

// 重新向量化接口限流为 GLOBAL/IP 各 2 次/秒，批量提交按最小间隔串行，控制在该阈值内
const REVECTORIZE_MIN_INTERVAL_MS = 600;

// 管理页每页条数候选：列表行较轻，默认 20 起步
const PAGE_SIZE_OPTIONS = [20, 50, 100, 200];

const sleep = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms));

// 单条重新向量化：命中限流按 1s → 2.5s → 5s 退避重试，序列耗尽或非限流错误则返回 false
async function revectorizeWithRetry(id: number): Promise<boolean> {
  for (let attempt = 0; ; attempt += 1) {
    try {
      await knowledgeBaseApi.revectorize(id);
      return true;
    } catch (error) {
      const delay = isRateLimitError(error) ? getUploadRetryDelay(attempt) : null;
      if (delay === null) return false;
      await sleep(delay);
    }
  }
}

// 状态图标组件
function StatusIcon({ status }: { status: VectorStatus }) {
  switch (status) {
    case 'COMPLETED':
      return <CheckCircle className="w-4 h-4 shrink-0 text-green-500" />;
    case 'PROCESSING':
      return <Loader2 className="w-4 h-4 shrink-0 text-blue-500 animate-spin" />;
    case 'PENDING':
      return <Clock className="w-4 h-4 shrink-0 text-yellow-500" />;
    case 'FAILED':
      return <AlertCircle className="w-4 h-4 shrink-0 text-red-500" />;
    default:
      return <CheckCircle className="w-4 h-4 shrink-0 text-green-500" />;
  }
}

// 状态文本
function getStatusText(status: VectorStatus): string {
  switch (status) {
    case 'COMPLETED':
      return '已完成';
    case 'PROCESSING':
      return '处理中';
    case 'PENDING':
      return '待处理';
    case 'FAILED':
      return '失败';
    default:
      return '未知';
  }
}

// 分类徽标：category 按斜杠分段（一级/二级/三级）逐级渲染徽章，一级为灰底、其余为强调色；
// 徽章固定横排单行不换行，列宽不足时从尾部裁切，完整分类名通过 title 悬浮查看
function CategoryBadge({ category }: { category: string }) {
  const parts = category.split('/').filter(Boolean);
  if (parts.length === 0) return null;
  return (
    <span className="inline-flex min-w-0 items-center gap-1 overflow-hidden" title={category}>
      {parts.map((part, index) => (
        <span
          key={`${part}-${index}`}
          className={`px-1 py-0.5 rounded text-xs whitespace-nowrap ${
            index === 0
              ? 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300'
              : 'bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400'
          }`}
        >
          {part}
        </span>
      ))}
    </span>
  );
}

// 统计卡片组件
function StatCard({
  icon: Icon,
  label,
  value,
  color,
}: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: number;
  color: string;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      className="bg-white dark:bg-slate-800 rounded-xl p-6 shadow-sm border border-slate-100 dark:border-slate-700"
    >
      <div className="flex items-center gap-4">
        <div className={`p-3 rounded-lg ${color}`}>
          <Icon className="w-6 h-6 text-white" />
        </div>
        <div>
            <p className="text-sm text-slate-500 dark:text-slate-400">{label}</p>
            <p className="text-2xl font-bold text-slate-800 dark:text-white">{value.toLocaleString()}</p>
        </div>
      </div>
    </motion.div>
  );
}

export default function KnowledgeBaseManagePage({ onUpload, onChat }: KnowledgeBaseManagePageProps) {
  const location = useLocation();
  const [stats, setStats] = useState<KnowledgeBaseStats | null>(null);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  // 服务端分页：total 为当前筛选条件下的总条数，knowledgeBases 仅为当前页数据
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(true);
  const [searchKeyword, setSearchKeyword] = useState('');
  const [sortBy, setSortBy] = useState<SortOption>('time');
  const [categories, setCategories] = useState<string[]>([]);
  // 分类筛选：单下拉承载分类树，值为 ''（全部）/ '一级' / '一级/二级'
  const [categoryTree, setCategoryTree] = useState<CategoryTreeNode[]>([]);
  const [selectedCategory, setSelectedCategory] = useState('');
  // 状态筛选（前端过滤）：方便筛出失败项后全选批量重新向量化
  const [statusFilter, setStatusFilter] = useState<'' | VectorStatus>('');
  const [deleteItem, setDeleteItem] = useState<KnowledgeBaseItem | null>(null);
  const [deleting, setDeleting] = useState(false);

  // 批量勾选与批量分类
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [batchCategoryModalOpen, setBatchCategoryModalOpen] = useState(false);
  const [batchCategorySaving, setBatchCategorySaving] = useState(false);
  const [batchCategoryError, setBatchCategoryError] = useState('');

  // 批量重新向量化（失败项重试）
  const [batchRevectorizing, setBatchRevectorizing] = useState(false);
  const [revectorizeProgress, setRevectorizeProgress] = useState<{ done: number; total: number } | null>(null);
  const [batchNotice, setBatchNotice] = useState('');

  // 批量删除
  const [batchDeleteOpen, setBatchDeleteOpen] = useState(false);
  const [batchDeleting, setBatchDeleting] = useState(false);
  const [batchDeleteError, setBatchDeleteError] = useState('');
  const [batchDeleteNotice, setBatchDeleteNotice] = useState('');

  // 解析任务（批次进度）
  const [batchDrawerOpen, setBatchDrawerOpen] = useState(false);
  const [initialBatchId, setInitialBatchId] = useState<number | null>(
    (location.state as { openBatchId?: number } | null)?.openBatchId ?? null
  );
  const [batchSummaries, setBatchSummaries] = useState<KbBatchSummary[]>([]);
  const activeBatchCount = useMemo(
    () => batchSummaries.filter(batch => batch.status === 'PROCESSING').length,
    [batchSummaries]
  );

  // 分类编辑状态
  const [editingCategoryId, setEditingCategoryId] = useState<number | null>(null);
  const [editingCategoryValue, setEditingCategoryValue] = useState('');
  const [savingCategory, setSavingCategory] = useState(false);
  const categoryInputRef = useRef<HTMLInputElement>(null);

  // 重新向量化状态
  const [revectorizing, setRevectorizing] = useState<number | null>(null);

  // 分页查询参数：关键词 / 分类前缀 / 状态 / 排序全部下沉到后端，筛选变化时重置页码见下方 effect
  const listParams = useMemo(
    () => ({
      page,
      size: pageSize,
      sortBy,
      vectorStatus: statusFilter || undefined,
      keyword: searchKeyword,
      category: selectedCategory,
    }),
    [page, pageSize, sortBy, statusFilter, searchKeyword, selectedCategory]
  );

  // 应用分页结果：删除/筛选导致页码越界时回退到最后一页并触发重新加载
  const applyListPage = useCallback(
    (data: KnowledgeBasePage) => {
      setTotal(data.total);
      const totalPages = Math.max(1, Math.ceil(data.total / data.size));
      if (page > totalPages - 1) {
        setPage(totalPages - 1);
        return;
      }
      setKnowledgeBases(data.items);
    },
    [page]
  );

  // 加载数据（不显示loading状态，用于轮询）
  const loadDataSilent = useCallback(async () => {
    try {
      const [statsData, listPage, categoryList, tree, batchList] = await Promise.all([
        knowledgeBaseApi.getStatistics(),
        knowledgeBaseApi.listKnowledgeBasesPage(listParams),
        knowledgeBaseApi.getAllCategories(),
        knowledgeBaseApi.getCategoryTree().catch(() => [] as CategoryTreeNode[]),
        knowledgeBaseApi.listUploadBatches(20),
      ]);
      setStats(statsData);
      applyListPage(listPage);
      setCategories(categoryList);
      setCategoryTree(tree);
      setBatchSummaries(batchList);
    } catch (error) {
      console.error('加载数据失败:', error);
    }
  }, [listParams, applyListPage]);

  // 加载数据
  const loadData = useCallback(async () => {
    try {
      setLoading(true);
      const [statsData, listPage, categoryList, tree, batchList] = await Promise.all([
        knowledgeBaseApi.getStatistics(),
        knowledgeBaseApi.listKnowledgeBasesPage(listParams),
        knowledgeBaseApi.getAllCategories(),
        knowledgeBaseApi.getCategoryTree().catch(() => [] as CategoryTreeNode[]),
        knowledgeBaseApi.listUploadBatches(20),
      ]);
      setStats(statsData);
      applyListPage(listPage);
      setCategories(categoryList);
      setCategoryTree(tree);
      setBatchSummaries(batchList);
    } catch (error) {
      console.error('加载数据失败:', error);
    } finally {
      setLoading(false);
    }
  }, [listParams, applyListPage]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // 是否存在筛选条件：用于空态区分「一条都没有」与「筛选无结果」
  const hasActiveFilter = Boolean(searchKeyword || selectedCategory || statusFilter);

  // 切换筛选条件时回到第一页并清空勾选
  useEffect(() => {
    setPage(0);
    setSelectedIds(new Set());
  }, [searchKeyword, sortBy, selectedCategory, statusFilter]);

  // 轮询：当前页或全局存在进行中任务时，每5秒刷新一次（processingCount 覆盖不在当前页的在处理项）
  useEffect(() => {
    const hasPendingItems = knowledgeBases.some(
      kb => kb.vectorStatus === 'PENDING' || kb.vectorStatus === 'PROCESSING'
    );
    const hasActiveBatches = batchSummaries.some(batch => batch.status === 'PROCESSING');
    const hasProcessingAnywhere = (stats?.processingCount ?? 0) > 0;

    if ((hasPendingItems || hasActiveBatches || hasProcessingAnywhere) && !loading) {
      const timer = setInterval(() => {
        loadDataSilent();
      }, 5000);

      return () => clearInterval(timer);
    }
  }, [knowledgeBases, batchSummaries, stats?.processingCount, loading, loadDataSilent]);

  // 重新向量化
  const handleRevectorize = async (id: number) => {
    try {
      setRevectorizing(id);
      await knowledgeBaseApi.revectorize(id);
      await loadDataSilent();
    } catch (error) {
      console.error('重新向量化失败:', error);
    } finally {
      setRevectorizing(null);
    }
  };

  // 批量重新向量化：只处理失败项，串行提交并展示进度
  const handleBatchRevectorize = async () => {
    const targets = knowledgeBases.filter(kb => selectedIds.has(kb.id) && kb.vectorStatus === 'FAILED');
    if (targets.length === 0) {
      setBatchNotice('所选知识库中没有失败项，无需重新向量化');
      return;
    }
    setBatchRevectorizing(true);
    setBatchNotice('');
    setRevectorizeProgress({ done: 0, total: targets.length });
    let success = 0;
    let failed = 0;
    for (let index = 0; index < targets.length; index += 1) {
      setRevectorizeProgress({ done: index, total: targets.length });
      const ok = await revectorizeWithRetry(targets[index].id);
      if (ok) {
        success += 1;
      } else {
        failed += 1;
      }
      if (index < targets.length - 1) {
        await sleep(REVECTORIZE_MIN_INTERVAL_MS);
      }
    }
    setRevectorizeProgress(null);
    setBatchRevectorizing(false);
    setSelectedIds(new Set());
    await loadDataSilent();
    setBatchNotice(
      failed > 0
        ? `已提交 ${success} 个重新向量化任务，${failed} 个失败，请重试`
        : `已提交 ${success} 个重新向量化任务`
    );
  };

  // 删除知识库
  const handleDelete = async () => {
    if (!deleteItem) return;
    try {
      setDeleting(true);
      await knowledgeBaseApi.deleteKnowledgeBase(deleteItem.id);
      setDeleteItem(null);
      await loadData();
    } catch (error) {
      console.error('删除失败:', error);
    } finally {
      setDeleting(false);
    }
  };

  // 下载知识库
    const handleDownload = async (kb: KnowledgeBaseItem) => {
        try {
            const blob = await knowledgeBaseApi.downloadKnowledgeBase(kb.id);
            const url = window.URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = kb.originalFilename;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);
            window.URL.revokeObjectURL(url);
        } catch (error) {
            console.error('下载失败:', error);
        }
  };

  // 开始编辑分类
  const handleStartEditCategory = (kb: KnowledgeBaseItem) => {
    setEditingCategoryId(kb.id);
    setEditingCategoryValue(kb.category || '');
    setTimeout(() => {
      categoryInputRef.current?.focus();
    }, 50);
  };

  // 取消编辑分类
  const handleCancelEditCategory = () => {
    setEditingCategoryId(null);
    setEditingCategoryValue('');
  };

  // 保存分类
  const handleSaveCategory = async (id: number) => {
    try {
      setSavingCategory(true);
      const categoryToSave = editingCategoryValue.trim() || null;
      await knowledgeBaseApi.updateCategory(id, categoryToSave);
      setEditingCategoryId(null);
      setEditingCategoryValue('');
      await loadData();
    } catch (error) {
      console.error('更新分类失败:', error);
    } finally {
      setSavingCategory(false);
    }
  };

  // 处理分类输入框按键
  const handleCategoryKeyDown = (e: React.KeyboardEvent, id: number) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleSaveCategory(id);
    } else if (e.key === 'Escape') {
      handleCancelEditCategory();
    }
  };

  // 搜索处理
  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    loadData();
  };

  // ========== 批量勾选与批量分类 ==========

  const allDisplayedSelected =
    knowledgeBases.length > 0 && knowledgeBases.every(kb => selectedIds.has(kb.id));

  const handleToggleAll = () => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      if (allDisplayedSelected) {
        knowledgeBases.forEach(kb => next.delete(kb.id));
      } else {
        knowledgeBases.forEach(kb => next.add(kb.id));
      }
      return next;
    });
  };

  const handleToggleSelect = (id: number) => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  };

  const handleBatchCategoryConfirm = async (category: string | null) => {
    if (selectedIds.size === 0) return;
    try {
      setBatchCategorySaving(true);
      setBatchCategoryError('');
      await knowledgeBaseApi.batchUpdateCategory([...selectedIds], category);
      setBatchCategoryModalOpen(false);
      setSelectedIds(new Set());
      await loadData();
    } catch (error) {
      setBatchCategoryError(error instanceof Error ? error.message : '批量分类失败，请重试');
    } finally {
      setBatchCategorySaving(false);
    }
  };

  // 批量删除：一次请求交给后端逐条删除，部分失败时给出提示
  const handleBatchDeleteConfirm = async () => {
    if (selectedIds.size === 0) return;
    try {
      setBatchDeleting(true);
      setBatchDeleteError('');
      const result = await knowledgeBaseApi.batchDeleteKnowledgeBases([...selectedIds]);
      setSelectedIds(new Set());
      setBatchDeleteOpen(false);
      await loadData();
      setBatchDeleteNotice(
        result.failedCount > 0
          ? `已删除 ${result.successCount} 个知识库，${result.failedCount} 个删除失败，请重试`
          : ''
      );
    } catch (error) {
      setBatchDeleteError(error instanceof Error ? error.message : '批量删除失败，请重试');
    } finally {
      setBatchDeleting(false);
    }
  };

  const openBatchDrawer = () => {
    setBatchDrawerOpen(true);
  };

  const closeBatchDrawer = () => {
    setBatchDrawerOpen(false);
    setInitialBatchId(null);
  };

  return (
    <div className="max-w-[1600px] mx-auto">
      {/* 页面标题 */}
      <div className="flex items-center justify-between mb-8">
        <div>
            <h1 className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-3">
            <Database className="w-7 h-7 text-primary-500" />
            知识库管理
          </h1>
            <p className="text-slate-500 dark:text-slate-400 mt-1">管理您的知识库文件，查看使用统计</p>
        </div>
        <div className="flex gap-3">
          <button
            onClick={onUpload}
            className="flex items-center gap-2 px-4 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600 transition-colors"
          >
            <Upload className="w-4 h-4" />
            上传知识库
          </button>
          <button
            onClick={openBatchDrawer}
            className="relative flex items-center gap-2 px-4 py-2 bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-200 rounded-lg hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
            title="查看批量上传的解析进度"
          >
            <ListChecks className="w-4 h-4" />
            解析任务
            {activeBatchCount > 0 && (
              <span className="absolute -top-1.5 -right-1.5 min-w-[1.25rem] h-5 px-1 bg-red-500 text-white text-xs rounded-full flex items-center justify-center">
                {activeBatchCount}
              </span>
            )}
          </button>
          <button
            onClick={onChat}
            className="flex items-center gap-2 px-4 py-2 bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-200 rounded-lg hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
          >
            <MessageSquare className="w-4 h-4" />
            学习帮手
          </button>
        </div>
      </div>
      {/* 统计卡片 */}
      {stats && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
          <StatCard
            icon={Database}
            label="知识库总数"
            value={stats.totalCount}
            color="bg-primary-500"
          />
          <StatCard
            icon={MessageSquare}
            label="总提问次数"
            value={stats.totalQuestionCount}
            color="bg-amber-500"
          />
          <StatCard
            icon={Eye}
            label="总访问次数"
            value={stats.totalAccessCount}
            color="bg-emerald-500"
          />
        </div>
      )}

      {/* 搜索和筛选栏 */}
        <div
            className="bg-white dark:bg-slate-800 rounded-xl p-4 shadow-sm border border-slate-100 dark:border-slate-700 mb-6">
        <div className="flex flex-wrap items-center gap-4">
          {/* 搜索框 */}
          <form onSubmit={handleSearch} className="flex-1 min-w-[200px]">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
              <input
                type="text"
                value={searchKeyword}
                onChange={(e) => setSearchKeyword(e.target.value)}
                placeholder="搜索知识库名称..."
                className="w-full pl-10 pr-4 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
              />
            </div>
          </form>

          {/* 排序选择 */}
          <Select
            variant="filter"
            value={sortBy}
            onChange={(e) => {
              setSortBy(e.target.value as SortOption);
              setSearchKeyword('');
              setSelectedCategory('');
            }}
          >
            <option value="time">按时间排序</option>
            <option value="status">按状态排序</option>
            <option value="size">按大小排序</option>
            <option value="access">按访问排序</option>
            <option value="question">按提问排序</option>
          </Select>

          {/* 状态筛选：前端过滤，筛出失败项后可全选批量重新向量化 */}
          <Select
            variant="filter"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as '' | VectorStatus)}
            title="按向量化状态筛选"
          >
            <option value="">全部状态</option>
            <option value="FAILED">失败</option>
            <option value="PROCESSING">处理中</option>
            <option value="PENDING">待处理</option>
            <option value="COMPLETED">已完成</option>
          </Select>

          {/* 分类筛选：一级/二级合并为单个下拉（有二级时缩进展示，值为「一级/二级」） */}
          <CategoryFilterSelect
            tree={categoryTree}
            value={selectedCategory}
            onChange={(next) => {
              setSelectedCategory(next);
              setSearchKeyword('');
            }}
          />
        </div>
      </div>

      {/* 批量删除结果提示 */}
      {batchDeleteNotice && (
        <div className="flex items-center gap-1.5 text-sm text-red-500 mb-3">
          <AlertCircle className="w-4 h-4" />
          {batchDeleteNotice}
        </div>
      )}

      {/* 批量向量化结果提示 */}
      {batchNotice && (
        <div className="flex items-center gap-1.5 text-sm text-slate-500 dark:text-slate-400 mb-3">
          <RefreshCw className="w-4 h-4" />
          {batchNotice}
        </div>
      )}

      {/* 知识库列表：窄窗口兜底为横向滚动，避免右侧操作列被裁掉 */}
        <div
            className="bg-white dark:bg-slate-800 rounded-xl shadow-sm border border-slate-100 dark:border-slate-700 overflow-x-auto">
        {loading ? (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
          </div>
        ) : total === 0 ? (
          hasActiveFilter ? (
            <div className="text-center py-20">
              <Database className="w-16 h-16 text-slate-300 mx-auto mb-4" />
              <p className="text-slate-500 dark:text-slate-400">当前筛选条件下没有知识库</p>
              <button
                onClick={() => {
                  setStatusFilter('');
                  setSearchKeyword('');
                  setSelectedCategory('');
                }}
                className="mt-4 text-primary-500 hover:text-primary-600"
              >
                清除筛选条件
              </button>
            </div>
          ) : (
            <div className="text-center py-20">
              <HardDrive className="w-16 h-16 text-slate-300 mx-auto mb-4" />
              <p className="text-slate-500 dark:text-slate-400">暂无知识库</p>
              <button
                onClick={onUpload}
                className="mt-4 text-primary-500 hover:text-primary-600"
              >
                上传第一个知识库
              </button>
            </div>
          )
        ) : (
          <table className="w-full min-w-[900px] table-fixed">
              {/* 固定列宽：名称列定宽（超长截断），剩余宽度全部让给分类列，保证徽章横排一行 */}
              <colgroup>
                <col className="w-11" />
                <col className="w-[260px]" />
                <col />
                <col className="w-[76px]" />
                <col className="w-[88px]" />
                <col className="w-[52px]" />
                <col className="w-[148px]" />
                <col className="w-[136px]" />
              </colgroup>
              <thead className="bg-slate-50 dark:bg-slate-700 border-b border-slate-100 dark:border-slate-600">
              <tr>
                  <th className="w-12 px-3 py-4">
                  <input
                    type="checkbox"
                    checked={allDisplayedSelected}
                    onChange={handleToggleAll}
                    className="w-4 h-4 rounded border-slate-300 text-primary-500 focus:ring-primary-500/30 cursor-pointer"
                    title="全选本页"
                  />
                </th>
                  <th className="text-left px-3 py-4 text-sm font-medium text-slate-600 dark:text-slate-300 whitespace-nowrap">
                  名称
                </th>
                  <th className="text-left px-3 py-4 text-sm font-medium text-slate-600 dark:text-slate-300 whitespace-nowrap">
                  分类
                </th>
                  <th className="text-left px-3 py-4 text-sm font-medium text-slate-600 dark:text-slate-300 whitespace-nowrap">
                  大小
                </th>
                  <th className="text-left px-3 py-4 text-sm font-medium text-slate-600 dark:text-slate-300 whitespace-nowrap">
                  状态
                </th>
                  <th className="text-left px-3 py-4 text-sm font-medium text-slate-600 dark:text-slate-300 whitespace-nowrap">
                  提问
                </th>
                  <th className="text-left px-3 py-4 text-sm font-medium text-slate-600 dark:text-slate-300 whitespace-nowrap">
                  上传时间
                </th>
                  <th className="text-right px-3 py-4 text-sm font-medium text-slate-600 dark:text-slate-300 whitespace-nowrap">
                  操作
                </th>
              </tr>
            </thead>
            <tbody>
              {knowledgeBases.map((kb, index) => (
                <motion.tr
                  key={kb.id}
                  initial={{ opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: Math.min(index, 10) * 0.05 }}
                  className="border-b border-slate-50 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-700/50 transition-colors"
                >
                  <td className="px-3 py-4">
                    <input
                      type="checkbox"
                      checked={selectedIds.has(kb.id)}
                      onChange={() => handleToggleSelect(kb.id)}
                      className="w-4 h-4 rounded border-slate-300 text-primary-500 focus:ring-primary-500/30 cursor-pointer"
                    />
                  </td>
                  <td className="px-3 py-4">
                    <div className="flex min-w-0 items-center gap-3">
                      <FileText className="w-5 h-5 shrink-0 text-slate-400" />
                      <div className="min-w-0">
                          {/* 名称与文件名去掉与分类列重复的前缀（如 system-design/framework/mybatis/），完整值悬浮可见 */}
                          <p className="truncate font-medium text-slate-800 dark:text-white" title={kb.name}>
                            {stripCategoryPrefix(kb.category, kb.name)}
                          </p>
                          <p className="truncate text-xs text-slate-400 dark:text-slate-500" title={kb.originalFilename}>
                            {stripCategoryPrefix(kb.category, kb.originalFilename)}
                          </p>
                      </div>
                    </div>
                  </td>
                  <td className="px-3 py-4">
                    <AnimatePresence mode="wait">
                      {editingCategoryId === kb.id ? (
                        <motion.div
                          key="editing"
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          exit={{ opacity: 0 }}
                          className="flex min-w-0 items-center gap-2"
                        >
                          <input
                            ref={categoryInputRef}
                            type="text"
                            value={editingCategoryValue}
                            onChange={(e) => setEditingCategoryValue(e.target.value)}
                            onKeyDown={(e) => handleCategoryKeyDown(e, kb.id)}
                            placeholder="输入分类名称"
                            list="category-suggestions"
                            title={editingCategoryValue}
                            className="min-w-0 flex-1 px-2 py-1 text-sm border border-primary-300 dark:border-primary-600 rounded focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white"
                            disabled={savingCategory}
                          />
                          <datalist id="category-suggestions">
                            {categories.map((cat) => (
                              <option key={cat} value={cat} />
                            ))}
                          </datalist>
                          <button
                            onClick={() => handleSaveCategory(kb.id)}
                            disabled={savingCategory}
                            className="p-1 shrink-0 text-green-600 dark:text-green-400 hover:bg-green-50 dark:hover:bg-green-900/20 rounded transition-colors disabled:opacity-50"
                            title="保存"
                          >
                            {savingCategory ? (
                              <Loader2 className="w-4 h-4 animate-spin" />
                            ) : (
                              <Check className="w-4 h-4" />
                            )}
                          </button>
                          <button
                            onClick={handleCancelEditCategory}
                            disabled={savingCategory}
                            className="p-1 shrink-0 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-600 rounded transition-colors disabled:opacity-50"
                            title="取消"
                          >
                            <X className="w-4 h-4" />
                          </button>
                        </motion.div>
                      ) : (
                        <motion.div
                          key="display"
                          initial={{ opacity: 0 }}
                          animate={{ opacity: 1 }}
                          exit={{ opacity: 0 }}
                          className="flex min-w-0 items-center gap-2 group/category"
                        >
                          {kb.category ? (
                            <CategoryBadge category={kb.category} />
                          ) : (
                              <span className="text-slate-400 dark:text-slate-500 text-xs whitespace-nowrap">未分类</span>
                          )}
                          <button
                            onClick={() => handleStartEditCategory(kb)}
                            className="p-1 shrink-0 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded opacity-0 group-hover/category:opacity-100 transition-all"
                            title="编辑分类"
                          >
                            <Edit3 className="w-3.5 h-3.5" />
                          </button>
                        </motion.div>
                      )}
                    </AnimatePresence>
                  </td>
                    <td className="px-3 py-4 text-sm text-slate-600 dark:text-slate-300">
                    <div className="truncate">{formatFileSize(kb.fileSize)}</div>
                  </td>
                  <td className="px-3 py-4">
                    {/* 失败态悬停展示向量化失败原因（后端 vectorError），未记录原因时给兜底文案 */}
                    <div
                      className={`flex min-w-0 items-center gap-2 ${
                        kb.vectorStatus === 'FAILED' ? 'cursor-help' : ''
                      }`}
                      title={
                        kb.vectorStatus === 'FAILED'
                          ? `失败原因：${kb.vectorError?.trim() || '未记录具体原因，可尝试重新向量化'}`
                          : undefined
                      }
                    >
                      <StatusIcon status={kb.vectorStatus} />
                        <span className="truncate text-sm text-slate-600 dark:text-slate-300">
                        {getStatusText(kb.vectorStatus)}
                      </span>
                    </div>
                  </td>
                    <td className="px-3 py-4 text-sm text-slate-600 dark:text-slate-300">
                    <div className="truncate">{kb.questionCount}</div>
                  </td>
                    <td className="px-3 py-4 text-sm text-slate-500 dark:text-slate-400">
                    <div className="truncate">{formatDate(kb.uploadedAt)}</div>
                  </td>
                  <td className="px-3 py-4 text-right">
                    <div className="flex items-center justify-end gap-1">
                      {/* 下载按钮 */}
                      <button
                        onClick={() => handleDownload(kb)}
                        className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors"
                        title="下载"
                      >
                        <Download className="w-4 h-4" />
                      </button>
                      {/* 重新向量化按钮（仅 FAILED 状态显示） */}
                      {kb.vectorStatus === 'FAILED' && (
                        <button
                          onClick={() => handleRevectorize(kb.id)}
                          disabled={revectorizing === kb.id}
                          className="p-2 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors disabled:opacity-50"
                          title="重新向量化"
                        >
                          <RefreshCw className={`w-4 h-4 ${revectorizing === kb.id ? 'animate-spin' : ''}`} />
                        </button>
                      )}
                      {/* 删除按钮 */}
                      <button
                        onClick={() => setDeleteItem(kb)}
                        className="p-2 text-slate-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/30 rounded-lg transition-colors"
                        title="删除"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    </div>
                  </td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {/* 分页条：放在表格卡片外，表格横向滚动时不受影响 */}
      {!loading && (
        <Pagination
          page={page}
          pageSize={pageSize}
          total={total}
          unit="个"
          pageSizeOptions={PAGE_SIZE_OPTIONS}
          onPageChange={(nextPage) => {
            setPage(nextPage);
            setSelectedIds(new Set());
          }}
          onPageSizeChange={(size) => {
            setPageSize(size);
            setPage(0);
            setSelectedIds(new Set());
          }}
        />
      )}

      {/* 删除确认对话框 */}
      <DeleteConfirmDialog
        open={deleteItem !== null}
        item={deleteItem}
        itemType="知识库"
        loading={deleting}
        onConfirm={handleDelete}
        onCancel={() => setDeleteItem(null)}
      />

      {/* 批量勾选浮动工具条 */}
      <AnimatePresence>
        {selectedIds.size > 0 && (
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: 20 }}
            className="fixed bottom-6 left-1/2 -translate-x-1/2 z-40 bg-slate-800 dark:bg-slate-700 text-white rounded-xl shadow-2xl px-5 py-3 flex items-center gap-4"
          >
            <span className="text-sm whitespace-nowrap">已选 {selectedIds.size} 个知识库</span>
            <button
              onClick={() => {
                setBatchCategoryError('');
                setBatchCategoryModalOpen(true);
              }}
              disabled={batchRevectorizing}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-primary-500 hover:bg-primary-600 rounded-lg text-sm transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <FolderTree className="w-4 h-4" />
              批量分类
            </button>
            <button
              onClick={handleBatchRevectorize}
              disabled={batchRevectorizing}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-emerald-500 hover:bg-emerald-600 rounded-lg text-sm transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              title="对所选知识库中向量化失败的项目重新向量化"
            >
              {batchRevectorizing ? (
                <Loader2 className="w-4 h-4 animate-spin" />
              ) : (
                <RefreshCw className="w-4 h-4" />
              )}
              {batchRevectorizing && revectorizeProgress
                ? `向量化中 ${revectorizeProgress.done + 1}/${revectorizeProgress.total}`
                : '批量向量化'}
            </button>
            <button
              onClick={() => {
                setBatchDeleteError('');
                setBatchDeleteOpen(true);
              }}
              disabled={batchRevectorizing}
              className="flex items-center gap-1.5 px-3 py-1.5 bg-red-500 hover:bg-red-600 text-white rounded-lg text-sm transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <Trash2 className="w-4 h-4" />
              批量删除
            </button>
            <button
              onClick={() => setSelectedIds(new Set())}
              className="p-1 text-slate-400 hover:text-white transition-colors"
              title="取消选择"
            >
              <X className="w-4 h-4" />
            </button>
          </motion.div>
        )}
      </AnimatePresence>

      {/* 批量分类弹窗 */}
      <BatchCategoryModal
        open={batchCategoryModalOpen}
        selectedCount={selectedIds.size}
        categories={categories}
        saving={batchCategorySaving}
        error={batchCategoryError}
        onConfirm={handleBatchCategoryConfirm}
        onSetUncategorized={() => handleBatchCategoryConfirm(null)}
        onClose={() => setBatchCategoryModalOpen(false)}
      />

      {/* 批量删除确认弹窗 */}
      <DeleteConfirmDialog
        open={batchDeleteOpen}
        item={null}
        itemType="知识库"
        loading={batchDeleting}
        customMessage={
          batchDeleteError ? (
            <span className="text-red-500">{batchDeleteError}</span>
          ) : (
            <span>
              确定要删除已选的 <strong>{selectedIds.size}</strong> 个知识库吗？删除后无法恢复。
            </span>
          )
        }
        onConfirm={handleBatchDeleteConfirm}
        onCancel={() => setBatchDeleteOpen(false)}
      />

      {/* 解析任务抽屉 */}
      <BatchTasksDrawer
        open={batchDrawerOpen}
        initialBatchId={initialBatchId}
        onClose={closeBatchDrawer}
      />
    </div>
  );
}

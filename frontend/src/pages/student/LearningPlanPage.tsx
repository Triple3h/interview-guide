import { useEffect, useMemo, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { learningApi } from '../../api/learning';
import type { LearningPlanItem, LearningPlanStatus } from '../../types/learning';
import { formatDateOnly } from '../../utils/date';
import DeleteConfirmDialog from '../../components/DeleteConfirmDialog';
import Select from '../../components/ui/Select';
import { ChevronLeft, ListTodo, Pencil, Plus, Trash2 } from 'lucide-react';

const STATUS_FILTERS: { value: LearningPlanStatus | 'ALL'; label: string }[] = [
  { value: 'ALL', label: '全部' },
  { value: 'PENDING', label: '待开始' },
  { value: 'IN_PROGRESS', label: '进行中' },
  { value: 'DONE', label: '已完成' },
];

const STATUS_BADGE: Record<LearningPlanStatus, string> = {
  PENDING: 'bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-300',
  IN_PROGRESS: 'bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400',
  DONE: 'bg-green-50 dark:bg-green-900/30 text-green-600 dark:text-green-400',
};

export default function LearningPlanPage() {
  const navigate = useNavigate();

  const [items, setItems] = useState<LearningPlanItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [statusFilter, setStatusFilter] = useState<LearningPlanStatus | 'ALL'>('ALL');

  const [editingItem, setEditingItem] = useState<LearningPlanItem | null>(null);
  const [formTopic, setFormTopic] = useState('');
  const [formGoal, setFormGoal] = useState('');
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [deleteConfirm, setDeleteConfirm] = useState<LearningPlanItem | null>(null);

  useEffect(() => {
    loadItems();
  }, []);

  const loadItems = async () => {
    setLoading(true);
    try {
      const list = await learningApi.listPlan();
      setItems(list);
    } catch (err) {
      console.error('加载学习计划失败', err);
    } finally {
      setLoading(false);
    }
  };

  const filteredItems = useMemo(() => {
    if (statusFilter === 'ALL') {
      return items;
    }
    return items.filter((i) => i.status === statusFilter);
  }, [items, statusFilter]);

  const stats = useMemo(() => ({
    total: items.length,
    pending: items.filter((i) => i.status === 'PENDING').length,
    inProgress: items.filter((i) => i.status === 'IN_PROGRESS').length,
    done: items.filter((i) => i.status === 'DONE').length,
  }), [items]);

  const openCreateModal = () => {
    setEditingItem(null);
    setFormTopic('');
    setFormGoal('');
    setFormError('');
  };

  const openEditModal = (item: LearningPlanItem) => {
    setEditingItem(item);
    setFormTopic(item.topic);
    setFormGoal(item.goal ?? '');
    setFormError('');
  };

  const handleSave = async () => {
    if (!formTopic.trim() || saving) {
      return;
    }
    setSaving(true);
    setFormError('');
    try {
      if (editingItem) {
        await learningApi.updatePlanItem(editingItem.id, {
          topic: formTopic.trim(),
          goal: formGoal.trim(),
        });
      } else {
        await learningApi.createPlanItem({
          topic: formTopic.trim(),
          goal: formGoal.trim() || undefined,
        });
      }
      setEditingItem(null);
      await loadItems();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '保存失败，请重试');
    } finally {
      setSaving(false);
    }
  };

  const handleStatusChange = async (item: LearningPlanItem, status: LearningPlanStatus) => {
    try {
      await learningApi.updatePlanStatus(item.id, status);
      setItems((prev) => prev.map((i) => (i.id === item.id ? { ...i, status } : i)));
    } catch (err) {
      console.error('更新计划状态失败', err);
    }
  };

  const handleDelete = async () => {
    if (!deleteConfirm) return;
    try {
      await learningApi.deletePlanItem(deleteConfirm.id);
      setItems((prev) => prev.filter((i) => i.id !== deleteConfirm.id));
      setDeleteConfirm(null);
    } catch (err) {
      console.error('删除计划条目失败', err);
    }
  };

  // 状态选择 + 编辑 / 删除：手机端在卡片底部一行、桌面端在右侧一列，两处共用
  const renderActions = (item: LearningPlanItem) => (
    <>
      <Select
        variant="compact"
        value={item.status}
        onChange={(e) => handleStatusChange(item, e.target.value as LearningPlanStatus)}
        title="调整状态"
      >
        <option value="PENDING">待开始</option>
        <option value="IN_PROGRESS">进行中</option>
        <option value="DONE">已完成</option>
      </Select>
      <button
        onClick={() => openEditModal(item)}
        className="p-2.5 md:p-2 text-slate-400 hover:text-primary-500 active:bg-slate-100 dark:active:bg-slate-700 rounded-lg transition-colors"
        title="编辑"
        aria-label="编辑计划条目"
      >
        <Pencil className="w-4 h-4" />
      </button>
      <button
        onClick={() => setDeleteConfirm(item)}
        className="p-2.5 md:p-2 text-slate-400 hover:text-red-500 active:bg-red-50 dark:active:bg-red-900/30 rounded-lg transition-colors"
        title="删除"
        aria-label="删除计划条目"
      >
        <Trash2 className="w-4 h-4" />
      </button>
    </>
  );

  return (
    <div className="max-w-5xl mx-auto md:pt-8 md:pb-10 md:px-4">
      {/* 头部：手机端标题降档、副标题隐藏，主操作 shrink-0 不被挤压 */}
      <div className="flex items-center justify-between gap-2 md:gap-3 mb-3 md:mb-6">
        <div className="flex items-center gap-2 md:gap-3 min-w-0">
          <button
            onClick={() => navigate('/knowledgebase/chat')}
            className="p-2 shrink-0 rounded-xl border border-slate-200 dark:border-slate-600 text-slate-500 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
            title="返回学习帮手"
          >
            <ChevronLeft className="w-5 h-5" />
          </button>
          <div className="min-w-0">
            <h1 className="text-lg md:text-2xl font-bold text-slate-900 dark:text-white md:mb-1">学习计划</h1>
            <p className="hidden md:block text-slate-500 dark:text-slate-400 text-sm">
              和 AI 商定后固化的学习路径，AI 会在对话中自动跟进进度
            </p>
          </div>
        </div>
        <motion.button
          onClick={openCreateModal}
          className="shrink-0 flex items-center gap-1.5 px-3 md:px-4 py-2 bg-primary-500 text-white rounded-xl text-sm font-medium hover:bg-primary-600 transition-colors"
          whileHover={{ scale: 1.02 }}
          whileTap={{ scale: 0.98 }}
        >
          <Plus className="w-4 h-4" />
          添加条目
        </motion.button>
      </div>

      {/* 统计 + 筛选：手机端把计数并进筛选胶囊，省掉一整行统计 */}
      <div className="flex flex-wrap items-center gap-1.5 md:gap-3 mb-3 md:mb-5">
        <div className="hidden md:flex gap-2 text-xs">
          <span className="px-2.5 py-1 rounded-full bg-slate-50 dark:bg-slate-700 text-slate-600 dark:text-slate-300">
            共 {stats.total} 条
          </span>
          <span className="px-2.5 py-1 rounded-full bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-300">
            待开始 {stats.pending}
          </span>
          <span className="px-2.5 py-1 rounded-full bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400">
            进行中 {stats.inProgress}
          </span>
          <span className="px-2.5 py-1 rounded-full bg-green-50 dark:bg-green-900/30 text-green-600 dark:text-green-400">
            已完成 {stats.done}
          </span>
        </div>

        <div className="hidden md:block flex-1" />

        <div className="flex gap-1.5 md:gap-2">
          {STATUS_FILTERS.map((filter) => {
            const count = filter.value === 'ALL'
              ? stats.total
              : filter.value === 'PENDING'
                ? stats.pending
                : filter.value === 'IN_PROGRESS'
                  ? stats.inProgress
                  : stats.done;
            const active = statusFilter === filter.value;

            return (
              <button
                key={filter.value}
                onClick={() => setStatusFilter(filter.value)}
                className={`px-2.5 md:px-3 py-1.5 text-xs rounded-full transition-colors ${
                  active
                    ? 'bg-primary-500 text-white'
                    : 'bg-slate-50 dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-600'
                }`}
              >
                {filter.label}
                <span className={`md:hidden ml-1 ${active ? 'text-white/70' : 'text-slate-400'}`}>{count}</span>
              </button>
            );
          })}
        </div>
      </div>

      {/* 条目列表 */}
      {loading ? (
        <div className="text-center py-16">
          <motion.div
            className="w-6 h-6 border-2 border-primary-500 border-t-transparent rounded-full mx-auto"
            animate={{ rotate: 360 }}
            transition={{ duration: 1, repeat: Infinity, ease: 'linear' }}
          />
        </div>
      ) : filteredItems.length === 0 ? (
        <div className="text-center py-12 md:py-16 text-slate-400 dark:text-slate-500">
          <ListTodo className="w-10 h-10 md:w-12 md:h-12 mx-auto mb-3 opacity-50" />
          <p className="text-sm mb-2">
            {statusFilter !== 'ALL' ? '该状态下暂无条目' : '还没有学习计划'}
          </p>
          {!statusFilter || statusFilter === 'ALL' ? (
            <p className="text-xs">
              在学习帮手里说「帮我制定一个学习计划」，AI 会结合你的台账和方向给出提案，商定后固化到这里
            </p>
          ) : null}
        </div>
      ) : (
        <div className="space-y-2 md:space-y-3">
          {filteredItems.map((item) => (
            <motion.div
              key={item.id}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              className="bg-white dark:bg-slate-800 rounded-2xl p-3 md:p-4 shadow-sm border border-slate-100 dark:border-slate-700 group"
            >
              {/* 手机端：标题与目标各占整行，状态 + 日期 + 操作压到底部一行；桌面端保持「左内容 + 右操作」两列 */}
              <div className="flex flex-col md:flex-row md:items-start md:justify-between md:gap-3">
                <div className="min-w-0 flex-1 flex items-start gap-2 md:gap-3">
                  <span className="mt-0.5 w-5 h-5 md:w-6 md:h-6 rounded-full bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-300 text-[11px] md:text-xs flex items-center justify-center flex-shrink-0 font-medium">
                    {item.sortOrder + 1}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2 flex-wrap">
                      <h3
                        className={`font-semibold text-sm ${
                          item.status === 'DONE'
                            ? 'text-slate-400 dark:text-slate-500 line-through'
                            : 'text-slate-800 dark:text-white'
                        }`}
                      >
                        {item.topic}
                      </h3>
                      <span className={`hidden md:inline-block px-2 py-0.5 rounded-full text-xs ${STATUS_BADGE[item.status]}`}>
                        {item.statusLabel}
                      </span>
                    </div>
                    {item.goal && (
                      <p className="text-sm text-slate-600 dark:text-slate-300 mt-1 md:mt-1.5 leading-relaxed">{item.goal}</p>
                    )}
                    <p className="hidden md:block text-xs text-slate-400 dark:text-slate-500 mt-2">
                      更新于 {formatDateOnly(item.updatedAt)}
                    </p>
                  </div>
                </div>

                {/* 手机端底部一行：徽标 + 更新时间靠左，状态选择与操作靠右 */}
                <div className="md:hidden mt-2 flex items-center gap-2 min-w-0">
                  <span className={`px-2 py-0.5 rounded-full text-xs flex-shrink-0 ${STATUS_BADGE[item.status]}`}>
                    {item.statusLabel}
                  </span>
                  <span className="min-w-0 truncate text-[11px] text-slate-400 dark:text-slate-500">
                    更新于 {formatDateOnly(item.updatedAt)}
                  </span>
                  <div className="ml-auto flex items-center gap-0.5 flex-shrink-0">{renderActions(item)}</div>
                </div>

                <div className="hidden md:flex items-center gap-1 flex-shrink-0">{renderActions(item)}</div>
              </div>
            </motion.div>
          ))}
        </div>
      )}

      {/* 新建/编辑弹窗 */}
      <AnimatePresence>
        {editingItem !== null && (
          <>
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setEditingItem(null)}
              className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
            />
            <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
              <motion.div
                initial={{ opacity: 0, scale: 0.95, y: 20 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.95, y: 20 }}
                onClick={(e) => e.stopPropagation()}
                className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-md w-full p-6 border border-slate-100 dark:border-slate-700"
              >
                <h3 className="text-xl font-bold text-slate-900 dark:text-white mb-4">
                  {editingItem.id ? '编辑条目' : '添加条目'}
                </h3>

                <div className="space-y-4">
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      主题 *
                    </label>
                    <input
                      type="text"
                      value={formTopic}
                      onChange={(e) => setFormTopic(e.target.value)}
                      placeholder="如：系统学习 Redis 持久化"
                      className="w-full px-4 py-2.5 text-sm border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
                      autoFocus
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                      目标
                    </label>
                    <textarea
                      value={formGoal}
                      onChange={(e) => setFormGoal(e.target.value)}
                      placeholder="学到什么程度 / 为什么学，1-2 句话"
                      rows={2}
                      className="w-full px-4 py-2.5 text-sm border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400 resize-none"
                    />
                  </div>
                </div>

                {formError && <p className="mt-3 text-sm text-red-500">{formError}</p>}

                <div className="flex gap-2 md:gap-3 md:justify-end mt-6">
                  <button
                    onClick={() => setEditingItem(null)}
                    className="flex-1 md:flex-none px-4 py-2.5 md:py-2 text-sm text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-600 rounded-lg hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
                  >
                    取消
                  </button>
                  <button
                    onClick={handleSave}
                    disabled={!formTopic.trim() || saving}
                    className="flex-1 md:flex-none px-4 py-2.5 md:py-2 text-sm bg-primary-500 text-white rounded-lg hover:bg-primary-600 disabled:opacity-50 transition-colors"
                  >
                    {saving ? '保存中…' : '保存'}
                  </button>
                </div>
              </motion.div>
            </div>
          </>
        )}
      </AnimatePresence>

      {/* 删除确认 */}
      <DeleteConfirmDialog
        open={!!deleteConfirm}
        item={deleteConfirm ? { id: 0, title: deleteConfirm.topic } : null}
        itemType="计划条目"
        onConfirm={handleDelete}
        onCancel={() => setDeleteConfirm(null)}
      />
    </div>
  );
}

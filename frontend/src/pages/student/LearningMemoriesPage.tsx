import { useEffect, useMemo, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { learningApi } from '../../api/learning';
import type { LearningMemory, LearningMemoryKind } from '../../types/learning';
import { formatDateOnly } from '../../utils/date';
import DeleteConfirmDialog from '../../components/DeleteConfirmDialog';
import Select from '../../components/ui/Select';
import { AlertCircle, Brain, ChevronLeft, Loader2, Pencil, Plus, RefreshCw, Search, Trash2 } from 'lucide-react';

const KIND_FILTERS: { value: LearningMemoryKind | 'ALL'; label: string }[] = [
  { value: 'ALL', label: '全部' },
  { value: 'PREFERENCE', label: '偏好' },
  { value: 'QUESTION', label: '提问' },
  { value: 'MISCONCEPTION', label: '易错点' },
  { value: 'HABIT', label: '习惯' },
  { value: 'NOTE', label: '其他' },
];

const KIND_BADGE: Record<LearningMemoryKind, string> = {
  PREFERENCE: 'bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 border border-blue-300/30 dark:border-blue-400/30',
  QUESTION: 'bg-amber-50 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400 border border-amber-300/30 dark:border-amber-400/30',
  MISCONCEPTION: 'bg-red-50 dark:bg-red-900/30 text-red-600 dark:text-red-400 border border-red-300/30 dark:border-red-400/30',
  HABIT: 'bg-green-50 dark:bg-green-900/30 text-green-600 dark:text-green-400 border border-green-300/30 dark:border-green-400/30',
  NOTE: 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 border border-slate-300/30 dark:border-slate-400/30',
};

export default function LearningMemoriesPage() {
  const navigate = useNavigate();

  const [memories, setMemories] = useState<LearningMemory[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [keyword, setKeyword] = useState('');
  const [kindFilter, setKindFilter] = useState<LearningMemoryKind | 'ALL'>('ALL');
  const [searchOpen, setSearchOpen] = useState(false);

  const [editingMemory, setEditingMemory] = useState<Partial<LearningMemory> | null>(null);
  const [formKind, setFormKind] = useState<LearningMemoryKind>('NOTE');
  const [formContent, setFormContent] = useState('');
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [deleteConfirm, setDeleteConfirm] = useState<LearningMemory | null>(null);

  useEffect(() => {
    loadMemories();
  }, []);

  const loadMemories = async (search?: string) => {
    setLoading(true);
    setLoadError('');
    try {
      const list = await learningApi.listMemories(search);
      setMemories(list);
    } catch (err) {
      console.error('加载个人记忆失败', err);
      setLoadError('加载失败，请检查网络后重试');
    } finally {
      setLoading(false);
    }
  };

  const handleSearch = () => {
    loadMemories(keyword);
  };

  const filteredMemories = useMemo(() => {
    if (kindFilter === 'ALL') {
      return memories;
    }
    return memories.filter((memory) => memory.kind === kindFilter);
  }, [memories, kindFilter]);

  const stats = useMemo(() => ({
    total: memories.length,
    preference: memories.filter((m) => m.kind === 'PREFERENCE').length,
    question: memories.filter((m) => m.kind === 'QUESTION').length,
    misconception: memories.filter((m) => m.kind === 'MISCONCEPTION').length,
    habit: memories.filter((m) => m.kind === 'HABIT').length,
    note: memories.filter((m) => m.kind === 'NOTE').length,
  }), [memories]);

  const countFor = (value: LearningMemoryKind | 'ALL') => {
    if (value === 'ALL') return stats.total;
    if (value === 'PREFERENCE') return stats.preference;
    if (value === 'QUESTION') return stats.question;
    if (value === 'MISCONCEPTION') return stats.misconception;
    if (value === 'HABIT') return stats.habit;
    return stats.note;
  };

  const openCreateModal = () => {
    setEditingMemory({});
    setFormKind('NOTE');
    setFormContent('');
    setFormError('');
  };

  const openEditModal = (memory: LearningMemory) => {
    setEditingMemory(memory);
    setFormKind(memory.kind);
    setFormContent(memory.content);
    setFormError('');
  };

  const handleSave = async () => {
    if (!formContent.trim() || saving) {
      return;
    }
    setSaving(true);
    setFormError('');
    try {
      if (editingMemory?.id) {
        await learningApi.updateMemory(editingMemory.id, {
          kind: formKind,
          content: formContent.trim(),
        });
      } else {
        await learningApi.createMemory({
          kind: formKind,
          content: formContent.trim(),
        });
      }
      setEditingMemory(null);
      await loadMemories(keyword);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '保存失败，请重试');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteConfirm) return;
    try {
      await learningApi.deleteMemory(deleteConfirm.id);
      setMemories((prev) => prev.filter((m) => m.id !== deleteConfirm.id));
      setDeleteConfirm(null);
    } catch (err) {
      console.error('删除个人记忆失败', err);
      setDeleteConfirm(null);
      setLoadError('删除失败，请重新加载后再试');
    }
  };

  const renderActions = (memory: LearningMemory) => (
    <>
      <button
        onClick={() => openEditModal(memory)}
        className="p-2.5 md:p-2 text-slate-400 hover:text-primary-500 active:bg-slate-100 dark:active:bg-slate-700 rounded-lg transition-colors"
        title="编辑"
        aria-label="编辑记忆"
      >
        <Pencil className="w-4 h-4" />
      </button>
      <button
        onClick={() => setDeleteConfirm(memory)}
        className="p-2.5 md:p-2 text-slate-400 hover:text-red-500 active:bg-red-50 dark:active:bg-red-900/30 rounded-lg transition-colors"
        title="删除"
        aria-label="删除记忆"
      >
        <Trash2 className="w-4 h-4" />
      </button>
    </>
  );

  return (
    <div className="max-w-5xl mx-auto md:pt-8 md:pb-10 md:px-4">
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
            <h1 className="text-lg md:text-2xl font-bold text-slate-900 dark:text-white md:mb-1">个人记忆</h1>
            <p className="hidden md:block text-slate-500 dark:text-slate-400 text-sm">
              偏好、提过的问题和易错点，对话结束后会自动整理
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
          添加记忆
        </motion.button>
      </div>

      <div className="mb-3 md:mb-5">
        <div className="flex flex-wrap items-center gap-1.5 md:gap-3">
          <div className="hidden md:flex gap-2 text-xs">
            <span className="px-2.5 py-1 rounded-full bg-slate-50 dark:bg-slate-700 text-slate-600 dark:text-slate-300">
              共 {stats.total} 条
            </span>
          </div>

          <div className="hidden md:block flex-1" />

          <div className="flex items-center gap-1.5 md:gap-2 flex-wrap">
            {KIND_FILTERS.map((filter) => {
              const count = countFor(filter.value);
              const active = kindFilter === filter.value;

              return (
                <button
                  key={filter.value}
                  onClick={() => setKindFilter(filter.value)}
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

            <button
              onClick={() => setSearchOpen((prev) => !prev)}
              className={`md:hidden p-2.5 rounded-full transition-colors flex-shrink-0 ${keyword ? 'text-primary-500' : 'text-slate-400'}`}
              title="搜索记忆"
              aria-label="搜索记忆"
            >
              <Search className="w-4 h-4" />
            </button>

            <div className="hidden md:flex items-center gap-1.5 ml-2 flex-none">
              <input
                type="text"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
                placeholder="搜索记忆…"
                className="w-40 px-3 py-1.5 text-xs border border-slate-200 dark:border-slate-600 rounded-full focus:outline-none focus:ring-1 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
              />
              <button
                onClick={handleSearch}
                className="p-1.5 text-slate-400 hover:text-primary-500 rounded-full"
                title="搜索"
              >
                <Search className="w-4 h-4" />
              </button>
            </div>
          </div>
        </div>

        {searchOpen && (
          <div className="md:hidden mt-2 flex items-center gap-2">
            <input
              type="text"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
              placeholder="搜索记忆…"
              autoFocus
              className="flex-1 min-w-0 px-3 py-2 text-xs border border-slate-200 dark:border-slate-600 rounded-full focus:outline-none focus:ring-1 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
            />
            <button
              onClick={() => {
                handleSearch();
                setSearchOpen(false);
              }}
              className="shrink-0 px-3 py-1.5 text-xs rounded-full bg-primary-500 text-white font-medium"
            >
              搜索
            </button>
          </div>
        )}
      </div>

      {loading ? (
        <div className="flex justify-center py-24">
          <Loader2 className="w-8 h-8 animate-spin text-primary-500" />
        </div>
      ) : loadError ? (
        <div className="text-center py-20">
          <AlertCircle className="w-12 h-12 mx-auto mb-3 text-red-400 dark:text-red-500" />
          <p className="text-sm text-slate-500 dark:text-slate-400 mb-4">{loadError}</p>
          <button
            onClick={() => loadMemories(keyword)}
            className="inline-flex items-center gap-1.5 px-4 py-2.5 rounded-lg bg-primary-500 text-white text-sm font-medium hover:bg-primary-600 transition-colors"
          >
            <RefreshCw className="w-4 h-4" />
            重新加载
          </button>
        </div>
      ) : filteredMemories.length === 0 ? (
        <div className="text-center py-12 md:py-20 text-slate-400 dark:text-slate-500">
          <Brain className="w-12 h-12 md:w-16 md:h-16 mx-auto mb-3 opacity-50" />
          <p className="text-sm mb-2">
            {keyword || kindFilter !== 'ALL' ? '没有匹配的记忆' : '还没有个人记忆'}
          </p>
          <p className="text-xs">
            {keyword || kindFilter !== 'ALL'
              ? '换个关键词搜索，或切到「全部」看所有记忆'
              : '在学习帮手里对话，结束后会自动把偏好和提问记到这里'}
          </p>
        </div>
      ) : (
        <div className="space-y-2 md:space-y-3">
          {filteredMemories.map((memory) => (
            <motion.div
              key={memory.id}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              className="bg-white dark:bg-slate-800 rounded-2xl p-3 md:p-4 shadow-sm border border-slate-100 dark:border-slate-700 group max-md:border-0 max-md:bg-slate-50 max-md:shadow-none max-md:dark:bg-slate-800"
            >
              <div className="flex flex-col md:flex-row md:items-start md:justify-between md:gap-3">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className={`px-2 py-0.5 rounded-full text-xs ${KIND_BADGE[memory.kind]}`}>
                      {memory.kindLabel}
                    </span>
                  </div>
                  <p className="text-sm text-slate-600 dark:text-slate-300 mt-1 md:mt-1.5 leading-relaxed">
                    {memory.content}
                  </p>
                  <p className="hidden md:block text-xs text-slate-400 dark:text-slate-500 mt-2">
                    更新于 {formatDateOnly(memory.updatedAt)}
                  </p>
                </div>

                <div className="md:hidden mt-2 flex items-center gap-2 min-w-0">
                  <span className="min-w-0 truncate text-[11px] text-slate-400 dark:text-slate-500">
                    更新于 {formatDateOnly(memory.updatedAt)}
                  </span>
                  <div className="ml-auto flex items-center gap-0.5 flex-shrink-0">{renderActions(memory)}</div>
                </div>

                <div className="hidden md:flex items-center gap-1 flex-shrink-0">{renderActions(memory)}</div>
              </div>
            </motion.div>
          ))}
        </div>
      )}

      <AnimatePresence>
        {editingMemory && (
          <>
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setEditingMemory(null)}
              className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
            />
            <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
              <motion.div
                initial={{ opacity: 0, scale: 0.95, y: 20 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.95, y: 20 }}
                onClick={(e) => e.stopPropagation()}
                className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-md w-full p-4 md:p-6 border border-slate-100 dark:border-slate-700 max-h-[85vh] md:max-h-[90vh] overflow-y-auto"
              >
                <h3 className="text-xl md:text-2xl font-bold text-slate-900 dark:text-white mb-4">
                  {editingMemory.id ? '编辑记忆' : '添加记忆'}
                </h3>

                <div className="space-y-4">
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">类型 *</label>
                    <Select
                      variant="form"
                      value={formKind}
                      onChange={(e) => setFormKind(e.target.value as LearningMemoryKind)}
                    >
                      <option value="PREFERENCE">偏好</option>
                      <option value="QUESTION">提问</option>
                      <option value="MISCONCEPTION">易错点</option>
                      <option value="HABIT">习惯</option>
                      <option value="NOTE">其他</option>
                    </Select>
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">内容 *</label>
                    <textarea
                      value={formContent}
                      onChange={(e) => setFormContent(e.target.value)}
                      placeholder="一两句话，例如：讲解时给代码示例"
                      rows={3}
                      className="w-full px-4 py-2.5 text-sm border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400 resize-none"
                      autoFocus
                    />
                  </div>
                </div>

                {formError && <p className="mt-3 text-sm text-red-500">{formError}</p>}

                <div className="sticky bottom-0 z-10 flex gap-2 md:gap-3 md:justify-end mt-6 -mx-4 -mb-4 px-4 pt-3 pb-4 md:-mx-6 md:-mb-6 md:px-6 md:pb-6 border-t border-slate-100 dark:border-slate-700 bg-white dark:bg-slate-800">
                  <button
                    onClick={() => setEditingMemory(null)}
                    className="flex-1 md:flex-none px-4 py-2.5 md:py-2 text-sm text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-600 rounded-lg hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
                  >
                    取消
                  </button>
                  <button
                    onClick={handleSave}
                    disabled={!formContent.trim() || saving}
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

      <DeleteConfirmDialog
        open={!!deleteConfirm}
        item={deleteConfirm ? { id: 0, title: deleteConfirm.content } : null}
        itemType="记忆"
        onConfirm={handleDelete}
        onCancel={() => setDeleteConfirm(null)}
      />
    </div>
  );
}

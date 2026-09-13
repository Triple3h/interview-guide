import { useEffect, useMemo, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { learningApi } from '../../api/learning';
import type { LearningMastery, LearningRecord } from '../../types/learning';
import { formatDateOnly } from '../../utils/date';
import DeleteConfirmDialog from '../../components/DeleteConfirmDialog';
import Select from '../../components/ui/Select';
import { BookOpenCheck, ChevronLeft, Pencil, Plus, Search, Trash2 } from 'lucide-react';

const MASTERY_FILTERS: { value: LearningMastery | 'ALL'; label: string }[] = [
  { value: 'ALL', label: '全部' },
  { value: 'BEGINNER', label: '初学' },
  { value: 'INTERMEDIATE', label: '理解' },
  { value: 'ADVANCED', label: '熟练' },
];

const MASTERY_BADGE: Record<LearningMastery, string> = {
  BEGINNER: 'bg-amber-50 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400',
  INTERMEDIATE: 'bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400',
  ADVANCED: 'bg-green-50 dark:bg-green-900/30 text-green-600 dark:text-green-400',
};

export default function LearningRecordsPage() {
  const navigate = useNavigate();

  const [records, setRecords] = useState<LearningRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [masteryFilter, setMasteryFilter] = useState<LearningMastery | 'ALL'>('ALL');
  /** 手机端搜索默认收成一个图标，点开后才占一行（桌面端始终内联在筛选行里） */
  const [searchOpen, setSearchOpen] = useState(false);

  const [editingRecord, setEditingRecord] = useState<Partial<LearningRecord> | null>(null);
  const [formTopic, setFormTopic] = useState('');
  const [formSummary, setFormSummary] = useState('');
  const [formMastery, setFormMastery] = useState<LearningMastery>('BEGINNER');
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [deleteConfirm, setDeleteConfirm] = useState<LearningRecord | null>(null);

  useEffect(() => {
    loadRecords();
  }, []);

  const loadRecords = async (search?: string) => {
    setLoading(true);
    try {
      const list = await learningApi.list(search);
      setRecords(list);
    } catch (err) {
      console.error('加载学习台账失败', err);
    } finally {
      setLoading(false);
    }
  };

  const handleSearch = () => {
    loadRecords(keyword);
  };

  const filteredRecords = useMemo(() => {
    if (masteryFilter === 'ALL') {
      return records;
    }
    return records.filter((r) => r.mastery === masteryFilter);
  }, [records, masteryFilter]);

  const stats = useMemo(() => ({
    total: records.length,
    beginner: records.filter((r) => r.mastery === 'BEGINNER').length,
    intermediate: records.filter((r) => r.mastery === 'INTERMEDIATE').length,
    advanced: records.filter((r) => r.mastery === 'ADVANCED').length,
  }), [records]);

  const openCreateModal = () => {
    setEditingRecord({});
    setFormTopic('');
    setFormSummary('');
    setFormMastery('BEGINNER');
    setFormError('');
  };

  const openEditModal = (record: LearningRecord) => {
    setEditingRecord(record);
    setFormTopic(record.topic);
    setFormSummary(record.summary);
    setFormMastery(record.mastery);
    setFormError('');
  };

  const handleSave = async () => {
    if (!formTopic.trim() || !formSummary.trim() || saving) {
      return;
    }
    setSaving(true);
    setFormError('');
    try {
      if (editingRecord?.id) {
        await learningApi.update(editingRecord.id, {
          topic: formTopic.trim(),
          summary: formSummary.trim(),
          mastery: formMastery,
        });
      } else {
        await learningApi.create({
          topic: formTopic.trim(),
          summary: formSummary.trim(),
          mastery: formMastery,
        });
      }
      setEditingRecord(null);
      await loadRecords(keyword);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : '保存失败，请重试');
    } finally {
      setSaving(false);
    }
  };

  const handleMasteryChange = async (record: LearningRecord, mastery: LearningMastery) => {
    try {
      await learningApi.updateMastery(record.id, mastery);
      setRecords((prev) => prev.map((r) => (r.id === record.id ? { ...r, mastery } : r)));
    } catch (err) {
      console.error('更新掌握度失败', err);
    }
  };

  const handleDelete = async () => {
    if (!deleteConfirm) return;
    try {
      await learningApi.delete(deleteConfirm.id);
      setRecords((prev) => prev.filter((r) => r.id !== deleteConfirm.id));
      setDeleteConfirm(null);
    } catch (err) {
      console.error('删除学习记录失败', err);
    }
  };

  // 掌握度选择 + 编辑 / 删除：手机端在卡片底部一行、桌面端在右侧一列，两处共用
  const renderActions = (record: LearningRecord) => (
    <>
      <Select
        variant="compact"
        value={record.mastery}
        onChange={(e) => handleMasteryChange(record, e.target.value as LearningMastery)}
        title="调整掌握度"
      >
        <option value="BEGINNER">初学</option>
        <option value="INTERMEDIATE">理解</option>
        <option value="ADVANCED">熟练</option>
      </Select>
      <button
        onClick={() => openEditModal(record)}
        className="p-2.5 md:p-2 text-slate-400 hover:text-primary-500 active:bg-slate-100 dark:active:bg-slate-700 rounded-lg transition-colors"
        title="编辑"
        aria-label="编辑知识点"
      >
        <Pencil className="w-4 h-4" />
      </button>
      <button
        onClick={() => setDeleteConfirm(record)}
        className="p-2.5 md:p-2 text-slate-400 hover:text-red-500 active:bg-red-50 dark:active:bg-red-900/30 rounded-lg transition-colors"
        title="删除"
        aria-label="删除知识点"
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
            <h1 className="text-lg md:text-2xl font-bold text-slate-900 dark:text-white md:mb-1">学习台账</h1>
            <p className="hidden md:block text-slate-500 dark:text-slate-400 text-sm">学过的知识点与掌握程度，AI 会在对话中自动更新</p>
          </div>
        </div>
        <motion.button
          onClick={openCreateModal}
          className="shrink-0 flex items-center gap-1.5 px-3 md:px-4 py-2 bg-primary-500 text-white rounded-xl text-sm font-medium hover:bg-primary-600 transition-colors"
          whileHover={{ scale: 1.02 }}
          whileTap={{ scale: 0.98 }}
        >
          <Plus className="w-4 h-4" />
          记录知识点
        </motion.button>
      </div>

      {/* 统计 + 筛选：手机端把计数并进筛选胶囊、搜索收成一个图标，整块只占一行 */}
      <div className="mb-3 md:mb-5">
        <div className="flex flex-wrap items-center gap-1.5 md:gap-3">
          <div className="hidden md:flex gap-2 text-xs">
            <span className="px-2.5 py-1 rounded-full bg-slate-50 dark:bg-slate-700 text-slate-600 dark:text-slate-300">
              共 {stats.total} 个
            </span>
            <span className="px-2.5 py-1 rounded-full bg-amber-50 dark:bg-amber-900/30 text-amber-600 dark:text-amber-400">
              初学 {stats.beginner}
            </span>
            <span className="px-2.5 py-1 rounded-full bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400">
              理解 {stats.intermediate}
            </span>
            <span className="px-2.5 py-1 rounded-full bg-green-50 dark:bg-green-900/30 text-green-600 dark:text-green-400">
              熟练 {stats.advanced}
            </span>
          </div>

          <div className="hidden md:block flex-1" />

          <div className="flex items-center gap-1.5 md:gap-2">
            {MASTERY_FILTERS.map((filter) => {
              const count = filter.value === 'ALL'
                ? stats.total
                : filter.value === 'BEGINNER'
                  ? stats.beginner
                  : filter.value === 'INTERMEDIATE'
                    ? stats.intermediate
                    : stats.advanced;
              const active = masteryFilter === filter.value;

              return (
                <button
                  key={filter.value}
                  onClick={() => setMasteryFilter(filter.value)}
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

            {/* 手机端：搜索收成一个图标，点开才展开成一行 */}
            <button
              onClick={() => setSearchOpen((prev) => !prev)}
              className={`md:hidden p-1.5 rounded-full transition-colors flex-shrink-0 ${keyword ? 'text-primary-500' : 'text-slate-400'}`}
              title="搜索知识点"
              aria-label="搜索知识点"
            >
              <Search className="w-4 h-4" />
            </button>

            <div className="hidden md:flex items-center gap-1.5 ml-2 flex-none">
              <input
                type="text"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                onKeyPress={(e) => e.key === 'Enter' && handleSearch()}
                placeholder="搜索知识点…"
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
              onKeyPress={(e) => e.key === 'Enter' && handleSearch()}
              placeholder="搜索知识点…"
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

      {/* 记录列表 */}
      {loading ? (
        <div className="text-center py-16">
          <motion.div
            className="w-6 h-6 border-2 border-primary-500 border-t-transparent rounded-full mx-auto"
            animate={{ rotate: 360 }}
            transition={{ duration: 1, repeat: Infinity, ease: 'linear' }}
          />
        </div>
      ) : filteredRecords.length === 0 ? (
        <div className="text-center py-12 md:py-16 text-slate-400 dark:text-slate-500">
          <BookOpenCheck className="w-10 h-10 md:w-12 md:h-12 mx-auto mb-3 opacity-50" />
          <p className="text-sm mb-2">
            {keyword || masteryFilter !== 'ALL' ? '没有匹配的知识点' : '台账还是空的'}
          </p>
          {!keyword && masteryFilter === 'ALL' && (
            <p className="text-xs">在学习帮手里对话，AI 会自动把学到的知识点记到这里</p>
          )}
        </div>
      ) : (
        <div className="space-y-2 md:space-y-3">
          {filteredRecords.map((record) => (
            <motion.div
              key={record.id}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              className="bg-white dark:bg-slate-800 rounded-2xl p-3 md:p-4 shadow-sm border border-slate-100 dark:border-slate-700 group"
            >
              {/* 手机端：标题与总结各占整行，掌握度 + 日期 + 操作压到底部一行；桌面端保持「左内容 + 右操作」两列 */}
              <div className="flex flex-col md:flex-row md:items-start md:justify-between md:gap-3">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 flex-wrap">
                    <h3 className="font-semibold text-slate-800 dark:text-white text-sm">{record.topic}</h3>
                    <span className={`hidden md:inline-block px-2 py-0.5 rounded-full text-xs ${MASTERY_BADGE[record.mastery]}`}>
                      {record.masteryLabel}
                    </span>
                  </div>
                  <p className="text-sm text-slate-600 dark:text-slate-300 mt-1 md:mt-1.5 leading-relaxed">{record.summary}</p>
                  <p className="hidden md:block text-xs text-slate-400 dark:text-slate-500 mt-2">
                    更新于 {formatDateOnly(record.updatedAt)}
                  </p>
                </div>

                {/* 手机端底部一行：徽标 + 更新时间靠左，掌握度选择与操作靠右 */}
                <div className="md:hidden mt-2 flex items-center gap-2 min-w-0">
                  <span className={`px-2 py-0.5 rounded-full text-xs flex-shrink-0 ${MASTERY_BADGE[record.mastery]}`}>
                    {record.masteryLabel}
                  </span>
                  <span className="min-w-0 truncate text-[11px] text-slate-400 dark:text-slate-500">
                    更新于 {formatDateOnly(record.updatedAt)}
                  </span>
                  <div className="ml-auto flex items-center gap-0.5 flex-shrink-0">{renderActions(record)}</div>
                </div>

                <div className="hidden md:flex items-center gap-1 flex-shrink-0">{renderActions(record)}</div>
              </div>
            </motion.div>
          ))}
        </div>
      )}

      {/* 新建/编辑弹窗 */}
      <AnimatePresence>
        {editingRecord && (
          <>
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setEditingRecord(null)}
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
                  {editingRecord.id ? '编辑知识点' : '记录知识点'}
                </h3>

                <div className="space-y-4">
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">主题 *</label>
                    <input
                      type="text"
                      value={formTopic}
                      onChange={(e) => setFormTopic(e.target.value)}
                      placeholder="如：Redis 持久化"
                      className="w-full px-4 py-2.5 text-sm border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
                      autoFocus
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">总结 *</label>
                    <textarea
                      value={formSummary}
                      onChange={(e) => setFormSummary(e.target.value)}
                      placeholder="学到了什么，1-3 句话"
                      rows={3}
                      className="w-full px-4 py-2.5 text-sm border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400 resize-none"
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">掌握度</label>
                    <Select
                      variant="form"
                      value={formMastery}
                      onChange={(e) => setFormMastery(e.target.value as LearningMastery)}
                    >
                      <option value="BEGINNER">初学</option>
                      <option value="INTERMEDIATE">理解</option>
                      <option value="ADVANCED">熟练</option>
                    </Select>
                  </div>
                </div>

                {formError && <p className="mt-3 text-sm text-red-500">{formError}</p>}

                <div className="flex gap-2 md:gap-3 md:justify-end mt-6">
                  <button
                    onClick={() => setEditingRecord(null)}
                    className="flex-1 md:flex-none px-4 py-2.5 md:py-2 text-sm text-slate-600 dark:text-slate-400 border border-slate-200 dark:border-slate-600 rounded-lg hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
                  >
                    取消
                  </button>
                  <button
                    onClick={handleSave}
                    disabled={!formTopic.trim() || !formSummary.trim() || saving}
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
        itemType="知识点"
        onConfirm={handleDelete}
        onCancel={() => setDeleteConfirm(null)}
      />
    </div>
  );
}

import { useEffect, useMemo, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { useNavigate } from 'react-router-dom';
import { learningApi } from '../api/learning';
import type { LearningMastery, LearningRecord } from '../types/learning';
import { formatDateOnly } from '../utils/date';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
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

  return (
    <div className="max-w-5xl mx-auto pt-8 pb-10 px-4">
      {/* 头部 */}
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/knowledgebase/chat')}
            className="p-2 rounded-xl border border-slate-200 dark:border-slate-600 text-slate-500 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors"
            title="返回学习帮手"
          >
            <ChevronLeft className="w-5 h-5" />
          </button>
          <div>
            <h1 className="text-2xl font-bold text-slate-900 dark:text-white mb-1">学习台账</h1>
            <p className="text-slate-500 dark:text-slate-400 text-sm">学过的知识点与掌握程度，AI 会在对话中自动更新</p>
          </div>
        </div>
        <motion.button
          onClick={openCreateModal}
          className="flex items-center gap-1.5 px-4 py-2 bg-primary-500 text-white rounded-xl text-sm font-medium hover:bg-primary-600 transition-all"
          whileHover={{ scale: 1.02 }}
          whileTap={{ scale: 0.98 }}
        >
          <Plus className="w-4 h-4" />
          记录知识点
        </motion.button>
      </div>

      {/* 统计 + 筛选 */}
      <div className="flex flex-wrap items-center gap-3 mb-5">
        <div className="flex gap-2 text-xs">
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

        <div className="flex-1" />

        <div className="flex items-center gap-2">
          {MASTERY_FILTERS.map((filter) => (
            <button
              key={filter.value}
              onClick={() => setMasteryFilter(filter.value)}
              className={`px-3 py-1.5 text-xs rounded-full transition-colors ${
                masteryFilter === filter.value
                  ? 'bg-primary-500 text-white'
                  : 'bg-slate-50 dark:bg-slate-700 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-600'
              }`}
            >
              {filter.label}
            </button>
          ))}
          <div className="flex items-center gap-1.5 ml-2">
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
        <div className="text-center py-16 text-slate-400 dark:text-slate-500">
          <BookOpenCheck className="w-12 h-12 mx-auto mb-3 opacity-50" />
          <p className="text-sm mb-2">
            {keyword || masteryFilter !== 'ALL' ? '没有匹配的知识点' : '台账还是空的'}
          </p>
          {!keyword && masteryFilter === 'ALL' && (
            <p className="text-xs">在学习帮手里对话，AI 会自动把学到的知识点记到这里</p>
          )}
        </div>
      ) : (
        <div className="space-y-3">
          {filteredRecords.map((record) => (
            <motion.div
              key={record.id}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              className="bg-white dark:bg-slate-800 rounded-2xl p-4 shadow-sm border border-slate-100 dark:border-slate-700 group"
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2 flex-wrap">
                    <h3 className="font-semibold text-slate-800 dark:text-white text-sm">{record.topic}</h3>
                    <span className={`px-2 py-0.5 rounded-full text-xs ${MASTERY_BADGE[record.mastery]}`}>
                      {record.masteryLabel}
                    </span>
                  </div>
                  <p className="text-sm text-slate-600 dark:text-slate-300 mt-1.5 leading-relaxed">{record.summary}</p>
                  <p className="text-xs text-slate-400 dark:text-slate-500 mt-2">
                    更新于 {formatDateOnly(record.updatedAt)}
                  </p>
                </div>

                <div className="flex items-center gap-1 flex-shrink-0">
                  <select
                    value={record.mastery}
                    onChange={(e) => handleMasteryChange(record, e.target.value as LearningMastery)}
                    className="px-2 py-1 text-xs border border-slate-200 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-700 text-slate-600 dark:text-slate-300 focus:outline-none focus:ring-1 focus:ring-primary-500"
                    title="调整掌握度"
                  >
                    <option value="BEGINNER">初学</option>
                    <option value="INTERMEDIATE">理解</option>
                    <option value="ADVANCED">熟练</option>
                  </select>
                  <button
                    onClick={() => openEditModal(record)}
                    className="p-1.5 text-slate-400 hover:text-primary-500 rounded transition-colors"
                    title="编辑"
                  >
                    <Pencil className="w-4 h-4" />
                  </button>
                  <button
                    onClick={() => setDeleteConfirm(record)}
                    className="p-1.5 text-slate-400 hover:text-red-500 rounded transition-colors"
                    title="删除"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
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
                    <select
                      value={formMastery}
                      onChange={(e) => setFormMastery(e.target.value as LearningMastery)}
                      className="w-full px-4 py-2.5 text-sm border border-slate-200 dark:border-slate-600 rounded-xl bg-white dark:bg-slate-700 text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500"
                    >
                      <option value="BEGINNER">初学</option>
                      <option value="INTERMEDIATE">理解</option>
                      <option value="ADVANCED">熟练</option>
                    </select>
                  </div>
                </div>

                {formError && <p className="mt-3 text-sm text-red-500">{formError}</p>}

                <div className="flex justify-end gap-3 mt-6">
                  <button
                    onClick={() => setEditingRecord(null)}
                    className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400 hover:text-slate-800 dark:hover:text-white"
                  >
                    取消
                  </button>
                  <button
                    onClick={handleSave}
                    disabled={!formTopic.trim() || !formSummary.trim() || saving}
                    className="px-4 py-2 text-sm bg-primary-500 text-white rounded-lg hover:bg-primary-600 disabled:opacity-50"
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

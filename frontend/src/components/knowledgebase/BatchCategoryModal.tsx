import { useEffect, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { FolderTree, Loader2, X } from 'lucide-react';

interface BatchCategoryModalProps {
  open: boolean;
  selectedCount: number;
  categories: string[];
  saving: boolean;
  error?: string;
  onConfirm: (category: string | null) => void;
  onSetUncategorized: () => void;
  onClose: () => void;
}

export default function BatchCategoryModal({
  open,
  selectedCount,
  categories,
  saving,
  error,
  onConfirm,
  onSetUncategorized,
  onClose,
}: BatchCategoryModalProps) {
  const [category, setCategory] = useState('');

  useEffect(() => {
    if (open) {
      setCategory('');
    }
  }, [open]);

  const handleConfirm = () => {
    if (saving) return;
    onConfirm(category.trim());
  };

  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={saving ? undefined : onClose}
            className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
          />
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 20 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 20 }}
              onClick={e => e.stopPropagation()}
              className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-md w-full"
            >
              {/* 头部 */}
              <div className="px-6 py-4 border-b border-slate-100 dark:border-slate-700 flex items-center justify-between">
                <h3 className="font-bold text-slate-800 dark:text-white flex items-center gap-2">
                  <FolderTree className="w-5 h-5 text-primary-500" />
                  批量分类
                </h3>
                <button
                  onClick={saving ? undefined : onClose}
                  className="p-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-300 rounded"
                  aria-label="关闭"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>

              {/* 内容 */}
              <div className="px-6 py-5 space-y-4">
                <p className="text-sm text-slate-500 dark:text-slate-400">
                  将为已选的 <span className="font-semibold text-primary-600 dark:text-primary-400">{selectedCount}</span> 个知识库统一设置分类。
                </p>
                <div>
                  <label className="block text-sm font-medium text-slate-700 dark:text-slate-200 mb-1">
                    目标分类
                  </label>
                  <input
                    type="text"
                    value={category}
                    onChange={e => setCategory(e.target.value)}
                    onKeyDown={e => {
                      if (e.key === 'Enter') {
                        e.preventDefault();
                        handleConfirm();
                      }
                    }}
                    placeholder="输入新分类或从列表选择"
                    list="batch-category-suggestions"
                    autoFocus
                    disabled={saving}
                    className="w-full px-3 py-2 border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white disabled:opacity-50"
                  />
                  <datalist id="batch-category-suggestions">
                    {categories.map(cat => (
                      <option key={cat} value={cat} />
                    ))}
                  </datalist>
                  <p className="mt-1 text-xs text-slate-400 dark:text-slate-500">
                    可直接输入新分类名称，已有分类会自动联想。
                  </p>
                </div>
                {error && <p className="text-sm text-red-500">{error}</p>}
              </div>

              {/* 底部 */}
              <div className="flex items-center justify-between px-6 py-4 border-t border-slate-100 dark:border-slate-700">
                <button
                  onClick={onSetUncategorized}
                  disabled={saving}
                  className="text-sm text-slate-500 dark:text-slate-400 hover:text-red-500 dark:hover:text-red-400 transition-colors disabled:opacity-50"
                >
                  设为未分类
                </button>
                <div className="flex gap-3">
                  <button
                    onClick={onClose}
                    disabled={saving}
                    className="px-4 py-2 rounded-lg border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors disabled:opacity-50"
                  >
                    取消
                  </button>
                  <button
                    onClick={handleConfirm}
                    disabled={saving}
                    className="flex items-center gap-2 px-4 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600 transition-colors disabled:opacity-50"
                  >
                    {saving && <Loader2 className="w-4 h-4 animate-spin" />}
                    保存分类
                  </button>
                </div>
              </div>
            </motion.div>
          </div>
        </>
      )}
    </AnimatePresence>
  );
}

import { useEffect, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { userApi } from '../api/user';
import type { SaveUserPayload, UserProfile } from '../types/user';

const EMOJI_OPTIONS = ['🦊', '🐱', '🐼', '🦁', '🐧', '🐨', '🦉', '🐢', '🚀', '🌱', '📚', '🧠'];

interface UserProfileModalProps {
  open: boolean;
  mode: 'create' | 'edit';
  initial?: UserProfile | null;
  onClose: () => void;
  onSaved: (user: UserProfile) => void;
}

/**
 * 学习成员资料表单（新建 / 编辑共用）
 */
export default function UserProfileModal({ open, mode, initial, onClose, onSaved }: UserProfileModalProps) {
  const [nickname, setNickname] = useState('');
  const [avatarEmoji, setAvatarEmoji] = useState(EMOJI_OPTIONS[0]);
  const [occupation, setOccupation] = useState('');
  const [learningDirection, setLearningDirection] = useState('');
  const [currentLevel, setCurrentLevel] = useState('');
  const [learningGoal, setLearningGoal] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!open) {
      return;
    }
    setNickname(initial?.nickname ?? '');
    setAvatarEmoji(initial?.avatarEmoji ?? EMOJI_OPTIONS[Math.floor(Math.random() * EMOJI_OPTIONS.length)]);
    setOccupation(initial?.occupation ?? '');
    setLearningDirection(initial?.learningDirection ?? '');
    setCurrentLevel(initial?.currentLevel ?? '');
    setLearningGoal(initial?.learningGoal ?? '');
    setError('');
  }, [open, initial]);

  const handleSubmit = async () => {
    if (!nickname.trim() || saving) {
      return;
    }
    setSaving(true);
    setError('');
    try {
      const payload: SaveUserPayload = {
        nickname: nickname.trim(),
        avatarEmoji,
        occupation: occupation.trim() || undefined,
        learningDirection: learningDirection.trim() || undefined,
        currentLevel: currentLevel.trim() || undefined,
        learningGoal: learningGoal.trim() || undefined,
      };
      const saved = mode === 'create'
        ? await userApi.create(payload)
        : await userApi.update(initial!.id, payload);
      onSaved(saved);
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存失败，请重试');
    } finally {
      setSaving(false);
    }
  };

  return (
    <AnimatePresence>
      {open && (
        <>
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onClose}
            className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
          />
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4 overflow-y-auto">
            <motion.div
              initial={{ opacity: 0, scale: 0.95, y: 20 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.95, y: 20 }}
              onClick={(e) => e.stopPropagation()}
              className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-lg w-full p-6 border border-slate-100 dark:border-slate-700 my-8"
            >
              <h3 className="text-xl font-bold text-slate-900 dark:text-white mb-1">
                {mode === 'create' ? '新建学习成员' : '编辑学习资料'}
              </h3>
              <p className="text-sm text-slate-500 dark:text-slate-400 mb-5">
                资料会帮 AI 更好地因材施教，职业和学习方向会用于举例和难度把控
              </p>

              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">昵称 *</label>
                  <input
                    type="text"
                    value={nickname}
                    onChange={(e) => setNickname(e.target.value)}
                    placeholder="怎么称呼你？"
                    className="w-full px-4 py-2.5 text-sm border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">头像</label>
                  <div className="flex flex-wrap gap-2">
                    {EMOJI_OPTIONS.map((emoji) => (
                      <button
                        key={emoji}
                        type="button"
                        onClick={() => setAvatarEmoji(emoji)}
                        className={`w-9 h-9 rounded-xl text-xl flex items-center justify-center transition-all ${
                          avatarEmoji === emoji
                            ? 'bg-primary-50 dark:bg-primary-900/40 ring-2 ring-primary-500'
                            : 'bg-slate-50 dark:bg-slate-700 hover:bg-slate-100 dark:hover:bg-slate-600'
                        }`}
                      >
                        {emoji}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">职业</label>
                    <input
                      type="text"
                      value={occupation}
                      onChange={(e) => setOccupation(e.target.value)}
                      placeholder="如：后端工程师 / 教师"
                      className="w-full px-3 py-2 text-sm border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">学习方向</label>
                    <input
                      type="text"
                      value={learningDirection}
                      onChange={(e) => setLearningDirection(e.target.value)}
                      placeholder="如：Java 后端 / 英语口语"
                      className="w-full px-3 py-2 text-sm border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
                    />
                  </div>
                </div>

                <div>
                  <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">当前水平</label>
                  <input
                    type="text"
                    value={currentLevel}
                    onChange={(e) => setCurrentLevel(e.target.value)}
                    placeholder="如：会用 Redis 但没系统学过"
                    className="w-full px-3 py-2 text-sm border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
                  />
                </div>

                <div>
                  <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">学习目标</label>
                  <textarea
                    value={learningGoal}
                    onChange={(e) => setLearningGoal(e.target.value)}
                    placeholder="如：三个月内系统补齐分布式基础"
                    rows={2}
                    className="w-full px-3 py-2 text-sm border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400 resize-none"
                  />
                </div>
              </div>

              {error && (
                <p className="mt-4 text-sm text-red-500">{error}</p>
              )}

              <div className="flex justify-end gap-3 mt-6">
                <button
                  onClick={onClose}
                  className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400 hover:text-slate-800 dark:hover:text-white"
                >
                  取消
                </button>
                <button
                  onClick={handleSubmit}
                  disabled={!nickname.trim() || saving}
                  className="px-5 py-2 text-sm bg-primary-500 text-white rounded-lg hover:bg-primary-600 disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {saving ? '保存中…' : '保存'}
                </button>
              </div>
            </motion.div>
          </div>
        </>
      )}
    </AnimatePresence>
  );
}

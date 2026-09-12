import { useEffect, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { ChevronDown, Pencil, Plus, UserRound } from 'lucide-react';
import { userApi } from '../api/user';
import type { UserProfile } from '../types/user';
import UserProfileModal from './UserProfileModal';

interface UserMenuProps {
  /** 当前成员（页头展示与编辑回填用） */
  current: UserProfile | null;
  /** Agent 回答进行中时锁定切换/新建，避免流式回答记到别的成员名下 */
  locked?: boolean;
  /** 已确认切换（含新建后自动进入）；storeUser 与页面数据重载由页面负责 */
  onSwitch: (user: UserProfile) => void;
  /** 编辑保存后同步页头的当前成员 */
  onProfileSaved: (user: UserProfile) => void;
}

/**
 * 页头成员菜单：切换成员（轻确认）、新建成员、编辑当前资料
 * 冷启动（首次进入/本地身份失效）仍由 UserGate 全屏选人负责
 */
export default function UserMenu({ current, locked = false, onSwitch, onProfileSaved }: UserMenuProps) {
  const [open, setOpen] = useState(false);
  const [members, setMembers] = useState<UserProfile[]>([]);
  const [switchTarget, setSwitchTarget] = useState<UserProfile | null>(null);
  const [editOpen, setEditOpen] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);

  const toggleMenu = () => {
    if (open) {
      setOpen(false);
      return;
    }
    setOpen(true);
    userApi.list()
      .then(setMembers)
      .catch((err) => console.error('加载学习成员失败', err));
  };

  // Escape 关菜单（子弹窗打开时先留给他们自己处理）
  useEffect(() => {
    if (!open) {
      return;
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !switchTarget && !editOpen && !createOpen) {
        setOpen(false);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, switchTarget, editOpen, createOpen]);

  const others = members.filter((m) => m.id !== current?.id);

  const confirmSwitch = () => {
    if (!switchTarget) return;
    const target = switchTarget;
    setSwitchTarget(null);
    setOpen(false);
    onSwitch(target);
  };

  return (
    <div className="relative flex-shrink-0">
      {/* 触发器：复用原页头「以人为中心」的展示，提示语改为成员操作 */}
      <button
        onClick={toggleMenu}
        className="group flex items-center gap-3"
        title="切换 / 新建学习成员"
      >
        <span className="w-9 h-9 md:w-10 md:h-10 rounded-xl bg-primary-600/10 dark:bg-primary-400/15 ring-1 ring-primary-600/20 dark:ring-primary-400/30 flex items-center justify-center text-lg md:text-xl leading-none group-hover:ring-primary-500/50 transition-all">
          {current?.avatarEmoji || '🙂'}
        </span>
        <span className="text-left">
          <span className="flex items-center gap-1">
            <span className="text-sm md:text-base font-semibold text-slate-900 dark:text-slate-50 truncate max-w-24 md:max-w-32">
              {current?.nickname || '学员'}
            </span>
            <ChevronDown
              className={`w-3.5 h-3.5 text-slate-400 transition-transform ${open ? 'rotate-180' : ''}`}
            />
          </span>
          <span className="hidden md:block text-xs text-slate-400 dark:text-slate-500">学习成员 · 切换 / 新建</span>
        </span>
      </button>

      {/* 下拉菜单 */}
      <AnimatePresence>
        {open && (
          <>
            <div className="fixed inset-0 z-40 md:z-30" onClick={() => setOpen(false)}/>
            <motion.div
              initial={{ opacity: 0, y: 12, scale: 0.98 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: 12, scale: 0.98 }}
              transition={{ duration: 0.15, ease: 'easeOut' }}
              className="fixed inset-x-3 bottom-3 z-50 max-h-[65vh] overflow-y-auto rounded-2xl border border-slate-100 dark:border-slate-600 bg-white dark:bg-slate-800 shadow-xl shadow-slate-900/10 p-1.5 md:absolute md:inset-x-auto md:bottom-auto md:top-full md:left-0 md:mt-2 md:w-80 md:max-h-none md:overflow-visible"
            >
              {/* 当前成员：hover 出编辑 */}
              {current && (
                <div className="group flex items-center gap-3 rounded-xl px-3 py-2.5 hover:bg-slate-50 dark:hover:bg-slate-700/50">
                  <span className="w-9 h-9 rounded-lg bg-primary-600/10 dark:bg-primary-400/15 flex items-center justify-center text-lg leading-none flex-shrink-0">
                    {current.avatarEmoji || '🙂'}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-1.5">
                      <span className="font-medium text-slate-800 dark:text-white text-sm truncate">
                        {current.nickname}
                      </span>
                      <span className="text-xs text-primary-500 flex-shrink-0">当前</span>
                    </span>
                    <span className="block text-xs text-slate-400 dark:text-slate-500 truncate">
                      {current.learningDirection || current.occupation || '学习成员'}
                    </span>
                  </span>
                  <button
                    onClick={() => {
                      setOpen(false);
                      setEditOpen(true);
                    }}
                    className="p-1.5 text-slate-400 hover:text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors opacity-100 md:opacity-0 md:group-hover:opacity-100"
                    title="编辑资料"
                  >
                    <Pencil className="w-4 h-4"/>
                  </button>
                </div>
              )}

              {others.length > 0 && (
                <>
                  <div className="my-1 h-px bg-slate-100 dark:bg-slate-700 mx-2"/>
                  {others.map((user) => (
                    <button
                      key={user.id}
                      disabled={locked}
                      onClick={() => setSwitchTarget(user)}
                      title={locked ? '等回答结束后再切换' : `切换到「${user.nickname}」的学习空间`}
                      className={`w-full flex items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors ${
                        locked
                          ? 'opacity-50 cursor-not-allowed'
                          : 'hover:bg-slate-50 dark:hover:bg-slate-700/50'
                      }`}
                    >
                      <span className="w-9 h-9 rounded-lg bg-slate-50 dark:bg-slate-700 flex items-center justify-center text-lg leading-none flex-shrink-0">
                        {user.avatarEmoji || '🙂'}
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="block font-medium text-slate-700 dark:text-slate-200 text-sm truncate">
                          {user.nickname}
                        </span>
                        <span className="block text-xs text-slate-400 dark:text-slate-500 truncate">
                          {user.learningDirection || user.occupation || '点击切换'}
                        </span>
                      </span>
                    </button>
                  ))}
                </>
              )}

              <div className="my-1 h-px bg-slate-100 dark:bg-slate-700 mx-2"/>
              <button
                disabled={locked}
                title={locked ? '等回答结束后再新建' : undefined}
                onClick={() => {
                  setOpen(false);
                  setCreateOpen(true);
                }}
                className={`w-full flex items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors ${
                  locked
                    ? 'opacity-50 cursor-not-allowed'
                    : 'text-slate-500 dark:text-slate-400 hover:text-primary-500 hover:bg-primary-50/60 dark:hover:bg-primary-900/30'
                }`}
              >
                <span className="w-9 h-9 rounded-lg border-2 border-dashed border-slate-200 dark:border-slate-600 flex items-center justify-center flex-shrink-0">
                  <Plus className="w-4 h-4"/>
                </span>
                <span className="text-sm font-medium">新建成员</span>
              </button>
            </motion.div>
          </>
        )}
      </AnimatePresence>

      {/* 切换确认：换身份意味着之后的对话/台账都记到对方名下 */}
      <AnimatePresence>
        {switchTarget && (
          <>
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setSwitchTarget(null)}
              className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
            />
            <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
              <motion.div
                initial={{ opacity: 0, scale: 0.95, y: 20 }}
                animate={{ opacity: 1, scale: 1, y: 0 }}
                exit={{ opacity: 0, scale: 0.95, y: 20 }}
                onClick={(e) => e.stopPropagation()}
                className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-sm w-full p-6 border border-slate-100 dark:border-slate-700"
              >
                <div className="flex items-center gap-3 mb-4">
                  <span className="w-11 h-11 rounded-xl bg-slate-50 dark:bg-slate-700 flex items-center justify-center text-2xl flex-shrink-0">
                    {switchTarget.avatarEmoji || <UserRound className="w-6 h-6 text-slate-400"/>}
                  </span>
                  <div className="min-w-0">
                    <h3 className="text-base font-bold text-slate-900 dark:text-white truncate">
                      切换到「{switchTarget.nickname}」的学习空间？
                    </h3>
                  </div>
                </div>
                <p className="text-sm text-slate-500 dark:text-slate-400 leading-relaxed mb-5">
                  之后的对话、学习台账与学习计划都会记录在「{switchTarget.nickname}」名下，随时可以切换回来。
                </p>
                <div className="flex justify-end gap-3">
                  <button
                    onClick={() => setSwitchTarget(null)}
                    className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400 hover:text-slate-800 dark:hover:text-white"
                  >
                    取消
                  </button>
                  <button
                    onClick={confirmSwitch}
                    className="px-4 py-2 text-sm bg-primary-500 text-white rounded-lg hover:bg-primary-500/90 transition-colors"
                  >
                    切换
                  </button>
                </div>
              </motion.div>
            </div>
          </>
        )}
      </AnimatePresence>

      {/* 新建成员：创建成功即以新成员进入 */}
      <UserProfileModal
        open={createOpen}
        mode="create"
        onClose={() => setCreateOpen(false)}
        onSaved={(user) => {
          setCreateOpen(false);
          setOpen(false);
          onSwitch(user);
        }}
      />

      {/* 编辑当前成员资料 */}
      <UserProfileModal
        open={editOpen}
        mode="edit"
        initial={current}
        onClose={() => setEditOpen(false)}
        onSaved={(saved) => {
          setEditOpen(false);
          onProfileSaved(saved);
        }}
      />
    </div>
  );
}

import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { ChevronDown, KeyRound, LogOut, Pencil } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import UserProfileModal from './UserProfileModal';
import ChangePasswordModal from './ChangePasswordModal';

interface AccountMenuProps {
  /** Agent 回答进行中时锁定操作，避免流式过程中改资料/退出 */
  locked?: boolean;
  /**
   * default：页头里的「头像 + 昵称」触发器（桌面向下展开 / 手机底部弹出）
   * drawer：移动端导航抽屉底部整行入口（面板需盖在抽屉 z-60 之上）
   */
  variant?: 'default' | 'drawer';
}

/**
 * 账户菜单：编辑资料 / 修改密码 / 退出登录
 * （学员身份即登录账号，不再有切换成员的概念）
 */
export default function AccountMenu({ locked = false, variant = 'default' }: AccountMenuProps) {
  const { profile, logout, refreshProfile } = useAuth();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);

  useEffect(() => {
    if (!open) {
      return;
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !editOpen && !passwordOpen) {
        setOpen(false);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, editOpen, passwordOpen]);

  const handleLogout = async () => {
    if (loggingOut || locked) {
      return;
    }
    setLoggingOut(true);
    setOpen(false);
    try {
      await logout();
      navigate('/login', { replace: true });
    } finally {
      setLoggingOut(false);
    }
  };

  return (
    <div className="relative flex-shrink-0">
      {variant === 'drawer' ? (
        <button
          onClick={() => setOpen((prev) => !prev)}
          className="w-full flex items-center gap-2.5 px-3 py-2 rounded-lg bg-[var(--ov-panel)] border border-[var(--ov-border-soft)] text-left active:bg-[var(--ov-muted)] transition-colors"
          title="账户"
        >
          <span className="w-8 h-8 rounded-lg bg-primary-600/10 dark:bg-primary-400/15 flex items-center justify-center text-base leading-none flex-shrink-0">
            {profile?.avatarEmoji || '🙂'}
          </span>
          <span className="min-w-0 flex-1">
            <span className="block text-sm font-medium text-slate-800 dark:text-slate-100 truncate">
              {profile?.nickname || '学员'}
            </span>
            <span className="block text-xs text-slate-500 dark:text-slate-500 truncate">
              {profile?.username ? `@${profile.username}` : '学员账号'}
            </span>
          </span>
          <ChevronDown className={`w-4 h-4 text-slate-400 transition-transform flex-shrink-0 ${open ? 'rotate-180' : ''}`} />
        </button>
      ) : (
        <button
          onClick={() => setOpen((prev) => !prev)}
          className="group flex items-center gap-3"
          title="账户"
        >
          <span className="w-9 h-9 md:w-10 md:h-10 rounded-xl bg-primary-600/10 dark:bg-primary-400/15 ring-1 ring-primary-600/20 dark:ring-primary-400/30 flex items-center justify-center text-lg md:text-xl leading-none group-hover:ring-primary-500/50 transition-all">
            {profile?.avatarEmoji || '🙂'}
          </span>
          <span className="text-left">
            <span className="flex items-center gap-1">
              <span className="text-sm md:text-base font-semibold text-slate-900 dark:text-slate-50 truncate max-w-24 md:max-w-32">
                {profile?.nickname || '学员'}
              </span>
              <ChevronDown
                className={`w-3.5 h-3.5 text-slate-400 transition-transform ${open ? 'rotate-180' : ''}`}
              />
            </span>
            <span className="hidden md:block text-xs text-slate-400 dark:text-slate-500">账户 · 资料/密码/退出</span>
          </span>
        </button>
      )}

      <AnimatePresence>
        {open && (
          <>
            <div
              className={`fixed inset-0 ${variant === 'drawer' ? 'z-[65]' : 'z-40 md:z-30'}`}
              onClick={() => setOpen(false)}
            />
            <motion.div
              initial={{ opacity: 0, y: 12, scale: 0.98 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: 12, scale: 0.98 }}
              transition={{ duration: 0.15, ease: 'easeOut' }}
              className={`fixed inset-x-3 bottom-3 ${variant === 'drawer' ? 'z-[70]' : 'z-50'} max-h-[65vh] overflow-y-auto rounded-2xl border border-slate-100 dark:border-slate-600 bg-white dark:bg-slate-800 shadow-xl shadow-slate-900/10 p-1.5 md:absolute md:inset-x-auto md:bottom-auto md:top-full md:left-0 md:mt-2 md:w-72 md:max-h-none md:overflow-visible`}
            >
              <div className="flex items-center gap-3 rounded-xl px-3 py-2.5">
                <span className="w-9 h-9 rounded-lg bg-primary-600/10 dark:bg-primary-400/15 flex items-center justify-center text-lg leading-none flex-shrink-0">
                  {profile?.avatarEmoji || '🙂'}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block font-medium text-slate-800 dark:text-white text-sm truncate">
                    {profile?.nickname || '学员'}
                  </span>
                  <span className="block text-xs text-slate-400 dark:text-slate-500 truncate">
                    {profile?.username ? `@${profile.username}` : profile?.occupation || '学员账号'}
                  </span>
                </span>
              </div>

              <div className="my-1 h-px bg-slate-100 dark:bg-slate-700 mx-2" />

              <button
                disabled={locked}
                onClick={() => {
                  setOpen(false);
                  setEditOpen(true);
                }}
                className={`w-full flex items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors ${
                  locked
                    ? 'opacity-50 cursor-not-allowed'
                    : 'text-slate-600 dark:text-slate-300 hover:text-primary-600 dark:hover:text-primary-400 hover:bg-primary-50/60 dark:hover:bg-primary-900/30'
                }`}
              >
                <Pencil className="w-4 h-4 flex-shrink-0" />
                <span className="text-sm font-medium">编辑资料</span>
              </button>

              <button
                onClick={() => {
                  setOpen(false);
                  setPasswordOpen(true);
                }}
                className="w-full flex items-center gap-3 rounded-xl px-3 py-2.5 text-left text-slate-600 dark:text-slate-300 hover:text-primary-600 dark:hover:text-primary-400 hover:bg-primary-50/60 dark:hover:bg-primary-900/30 transition-colors"
              >
                <KeyRound className="w-4 h-4 flex-shrink-0" />
                <span className="text-sm font-medium">修改密码</span>
              </button>

              <button
                disabled={locked || loggingOut}
                onClick={handleLogout}
                className={`w-full flex items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors ${
                  locked || loggingOut
                    ? 'opacity-50 cursor-not-allowed'
                    : 'text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20'
                }`}
              >
                <LogOut className="w-4 h-4 flex-shrink-0" />
                <span className="text-sm font-medium">{loggingOut ? '退出中…' : '退出登录'}</span>
              </button>
            </motion.div>
          </>
        )}
      </AnimatePresence>

      <UserProfileModal
        open={editOpen}
        mode="edit"
        initial={profile}
        onClose={() => setEditOpen(false)}
        onSaved={() => {
          setEditOpen(false);
          void refreshProfile();
        }}
      />

      <ChangePasswordModal open={passwordOpen} onClose={() => setPasswordOpen(false)} />
    </div>
  );
}

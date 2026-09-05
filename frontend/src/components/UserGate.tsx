import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { Loader2, Plus, UserRound } from 'lucide-react';
import { userApi } from '../api/user';
import { getStoredUser, storeUser } from '../utils/currentUser';
import type { UserProfile } from '../types/user';
import UserProfileModal from './UserProfileModal';

interface UserGateProps {
  children: React.ReactNode;
}

/**
 * 选人门卫：首次进入（或本地成员失效）时选择/创建学习成员
 * 选定后写入 localStorage，请求层自动携带 X-User-Id
 */
export default function UserGate({ children }: UserGateProps) {
  const [status, setStatus] = useState<'loading' | 'ready' | 'pick'>('loading');
  const [users, setUsers] = useState<UserProfile[]>([]);
  const [createOpen, setCreateOpen] = useState(false);

  useEffect(() => {
    resolveCurrentUser();
  }, []);

  const resolveCurrentUser = async () => {
    try {
      const list = await userApi.list();
      setUsers(list);

      const stored = getStoredUser();
      const matched = stored ? list.find((u) => u.id === stored.id) : null;
      if (matched) {
        storeUser(matched);
        setStatus('ready');
        return;
      }

      // 只有一位成员时直接进入，减少摩擦
      if (list.length === 1) {
        storeUser(list[0]);
        setStatus('ready');
        return;
      }

      setStatus('pick');
    } catch (err) {
      console.error('加载学习成员失败', err);
      setStatus('pick');
    }
  };

  const handleSelect = (user: UserProfile) => {
    storeUser(user);
    setStatus('ready');
  };

  const handleCreated = (user: UserProfile) => {
    setCreateOpen(false);
    setUsers((prev) => [...prev, user]);
    storeUser(user);
    setStatus('ready');
  };

  if (status === 'ready') {
    return <>{children}</>;
  }

  if (status === 'loading') {
    return (
      <div className="flex items-center justify-center min-h-[60vh]">
        <Loader2 className="w-8 h-8 text-primary-500 animate-spin" />
      </div>
    );
  }

  return (
    <div className="min-h-[70vh] flex items-center justify-center px-4">
      <div className="w-full max-w-lg">
        <div className="text-center mb-8">
          <div className="w-16 h-16 rounded-2xl bg-primary-50 dark:bg-primary-900/30 flex items-center justify-center mx-auto mb-4">
            <UserRound className="w-8 h-8 text-primary-500" />
          </div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white mb-2">谁在学习？</h1>
          <p className="text-slate-500 dark:text-slate-400 text-sm">
            每位成员有独立的学习进度和知识台账，选择你的身份开始学习
          </p>
        </div>

        <div className="grid grid-cols-2 gap-3">
          {users.map((user) => (
            <motion.button
              key={user.id}
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
              onClick={() => handleSelect(user)}
              className="flex items-center gap-3 p-4 bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700 hover:border-primary-400 transition-all text-left"
            >
              <span className="w-11 h-11 rounded-xl bg-slate-50 dark:bg-slate-700 flex items-center justify-center text-2xl flex-shrink-0">
                {user.avatarEmoji || '🙂'}
              </span>
              <span className="min-w-0">
                <span className="block font-semibold text-slate-800 dark:text-white text-sm truncate">
                  {user.nickname}
                </span>
                <span className="block text-xs text-slate-400 truncate">
                  {user.learningDirection || user.occupation || '点击开始学习'}
                </span>
              </span>
            </motion.button>
          ))}

          <motion.button
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
            onClick={() => setCreateOpen(true)}
            className="flex items-center justify-center gap-2 p-4 rounded-2xl border-2 border-dashed border-slate-200 dark:border-slate-600 text-slate-400 hover:border-primary-400 hover:text-primary-500 transition-all"
          >
            <Plus className="w-5 h-5" />
            <span className="text-sm font-medium">新建成员</span>
          </motion.button>
        </div>

        {users.length === 0 && (
          <p className="text-center text-sm text-slate-400 mt-6">
            第一次使用？先创建一个学习成员吧
          </p>
        )}
      </div>

      <UserProfileModal
        open={createOpen}
        mode="create"
        onClose={() => setCreateOpen(false)}
        onSaved={handleCreated}
      />
    </div>
  );
}

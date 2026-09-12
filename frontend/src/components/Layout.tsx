import {Link, Outlet, useLocation, useNavigate} from 'react-router-dom';
import {AnimatePresence, motion} from 'framer-motion';
import {BookOpen, Calendar, Database, FileStack, Menu, MessageSquare, Moon, NotebookPen, Plus, Settings, Sparkles, Sun, Users, X,} from 'lucide-react';
import {useTheme} from '../hooks/useTheme';
import {useCallback, useEffect, useState} from 'react';
import {MobileTopBarActionContext, type MobileTopBarAction} from '../hooks/useMobileTopBarAction';
import UnifiedInterviewModal, {UnifiedInterviewConfig} from './UnifiedInterviewModal';
import {ROUTES} from '../constants/routes';
import {uuid} from '../utils/uuid';

interface NavItem {
  id: string;
  path: string;
  label: string;
  icon: React.ComponentType<{ className?: string; strokeWidth?: string | number }>;
  description?: string;
}

interface NavGroup {
  id: string;
  title: string;
  items: NavItem[];
}

export default function Layout() {
  const location = useLocation();
  const currentPath = location.pathname;
  const {theme, toggleTheme} = useTheme();
  const navigate = useNavigate();
  const [interviewModalPreset, setInterviewModalPreset] = useState<{
    defaultMode: 'text' | 'voice';
    defaultResumeId?: number;
    title: string;
    subtitle: string;
    startButtonText: string;
    hideModeSwitch?: boolean;
  } | null>(null);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  /** 当前页面注册到移动端顶栏「+」的动作，为空时不渲染按钮 */
  const [mobileTopBarAction, setMobileTopBarAction] = useState<MobileTopBarAction | null>(null);
  const registerMobileTopBarAction = useCallback((action: MobileTopBarAction | null) => {
    setMobileTopBarAction(action);
  }, []);

  // 路由变化时自动收起移动端导航抽屉
  useEffect(() => {
    setMobileNavOpen(false);
  }, [currentPath]);

  // 抽屉打开时锁定背景滚动（抽屉内只有导航，不做内部滚动）
  useEffect(() => {
    document.body.style.overflow = mobileNavOpen ? 'hidden' : '';
    return () => {
      document.body.style.overflow = '';
    };
  }, [mobileNavOpen]);

  const openInterviewModalWithResume = (resumeId: number) => {
    setInterviewModalPreset({
      defaultMode: 'text',
      defaultResumeId: resumeId,
      title: '开始模拟面试',
      subtitle: '配置面试参数，开始练习',
      startButtonText: '开始面试',
    });
  };

  // 通用「开始新面试」入口：不绑定简历，弹窗内可选（移动端顶栏 + 面试中心使用）
  const openInterviewModal = useCallback(() => {
    setInterviewModalPreset({
      defaultMode: 'text',
      hideModeSwitch: false,
      title: '开始模拟面试',
      subtitle: '选择面试模式和主题，快速开始',
      startButtonText: '开始面试',
    });
  }, []);

  const handleInterviewStart = (config: UnifiedInterviewConfig) => {
    setInterviewModalPreset(null);
    if (config.mode === 'text') {
      navigate(ROUTES.interviewCreate(uuid()), {
        state: {
          resumeId: config.resumeId,
          interviewConfig: {
            skillId: config.skillId,
            difficulty: config.difficulty,
            questionCount: config.questionCount,
            llmProvider: config.llmProvider,
          },
        },
      });
      return;
    }

    const params = new URLSearchParams({
      skillId: config.skillId,
      difficulty: config.difficulty,
    });
    navigate(`/voice-interview?${params.toString()}`, {
      state: {
        voiceConfig: {
          skillId: config.skillId,
          difficulty: config.difficulty,
          techEnabled: true,
          projectEnabled: true,
          hrEnabled: true,
          plannedDuration: config.plannedDuration,
          resumeId: config.resumeId,
          llmProvider: config.llmProvider,
        },
      },
    });
  };

  // 按业务模块组织的导航项
  const navGroups: NavGroup[] = [
    {
      id: 'interview',
      title: '面试准备',
      items: [
        { id: 'resumes', path: '/history', label: '简历管理', icon: FileStack, description: '管理简历，AI 分析' },
        { id: 'interview-hub', path: '/interview-hub', label: '模拟面试', icon: Sparkles, description: '文字/语音面试练习' },
        { id: 'interviews', path: '/interviews', label: '面试记录', icon: Users, description: '查看面试历史' },
        { id: 'interview-schedule', path: '/interview-schedule', label: '面试日程', icon: Calendar, description: '管理面试安排' },
      ],
    },
    {
      id: 'knowledge',
      title: '知识库',
      items: [
        { id: 'kb-manage', path: '/knowledgebase', label: '知识库管理', icon: Database, description: '管理知识文档' },
        { id: 'kb-interview', path: '/knowledgebase-interview', label: '知识库面试', icon: BookOpen, description: '题库维护与面试' },
        { id: 'chat', path: '/knowledgebase/chat', label: '学习帮手', icon: MessageSquare, description: 'AI 助教，进度自动记录' },
        { id: 'learning-records', path: '/learning/records', label: '学习台账', icon: NotebookPen, description: '已学知识点与掌握度' },
      ],
    },
    {
      id: 'system',
      title: '系统',
      items: [
        { id: 'settings', path: '/settings', label: '设置', icon: Settings, description: '管理模型和语音服务' },
      ],
    },
  ];

  // 判断当前页面是否匹配导航项
  const isActive = (path: string) => {
    if (path.startsWith('#')) return false;
    if (path === '/history') {
      return currentPath === '/history'
        || currentPath === '/'
        || currentPath.startsWith('/history/')
        || currentPath === '/upload';
    }
    if (path === '/interview-hub') {
      return currentPath === '/interview-hub'
        || currentPath === ROUTES.interview
        || currentPath.startsWith('/interview/')
        || currentPath.startsWith('/voice-interview');
    }
    if (path === '/knowledgebase') {
      return currentPath === '/knowledgebase' || currentPath === '/knowledgebase/upload';
    }
    return currentPath.startsWith(path);
  };

  // 移动端顶栏核心 tab（原底部标签栏，现移到左上角汉堡右侧）：其余导航走汉堡抽屉
  const mobileTabs: NavItem[] = [
    { id: 'mobile-hub', path: '/interview-hub', label: '面试', icon: Sparkles },
    { id: 'mobile-interviews', path: '/interviews', label: '记录', icon: Users },
    { id: 'mobile-chat', path: '/knowledgebase/chat', label: '学习帮手', icon: MessageSquare },
    { id: 'mobile-schedule', path: '/interview-schedule', label: '日程', icon: Calendar },
  ];

  return (
    <div className="flex flex-col md:flex-row min-h-dvh md:min-h-screen">
      {/* 移动端顶栏：汉堡 + 核心 tab 图标 + 当前页面的「新建」动作（桌面端隐藏） */}
      <header className="md:hidden sticky top-0 z-40 bg-[var(--ov-bg)]">
        <div className="flex items-center justify-between h-12 pr-3">
          <div className="flex items-center">
            <button
              onClick={() => setMobileNavOpen(true)}
              className="p-3 rounded-xl text-slate-700 dark:text-slate-200 active:bg-[var(--ov-muted)] transition-colors"
              title="全部导航"
              aria-label="全部导航"
            >
              <Menu className="w-5 h-5" />
            </button>
            {mobileTabs.map((tab) => {
              const active = isActive(tab.path);

              return (
                <Link
                  key={tab.id}
                  to={tab.path}
                  title={tab.label}
                  aria-label={tab.label}
                  className={`p-3 rounded-xl transition-colors
                    ${active
                      ? 'text-primary-600 dark:text-primary-400'
                      : 'text-slate-400 dark:text-slate-500'
                    }`}
                >
                  <tab.icon className="w-5 h-5" strokeWidth={active ? 2.4 : 2} />
                </Link>
              );
            })}
          </div>

          {mobileTopBarAction && (
            <button
              onClick={mobileTopBarAction.onClick}
              className="w-8 h-8 rounded-full border border-[var(--ov-border-strong)] text-slate-700 dark:text-slate-200 flex items-center justify-center active:bg-[var(--ov-muted)] transition-colors"
              title={mobileTopBarAction.title}
              aria-label={mobileTopBarAction.title}
            >
              <Plus className="w-4 h-4" />
            </button>
          )}
        </div>
      </header>

      {/* 左侧边栏：纸面浅底 + 网格纹理（仅桌面端） */}
      <aside className="hidden md:flex w-64 bg-[var(--ov-bg-soft)] border-r border-[var(--ov-border-soft)] fixed h-screen left-0 top-0 z-50 flex-col">
        {/* Logo */}
        <div className="p-6 border-b border-[var(--ov-border-soft)] flex items-center justify-between">
          <Link to="/history" className="flex items-center gap-3">
            <div className="w-10 h-10 bg-primary-600 dark:bg-primary-500 rounded-lg flex items-center justify-center text-white shadow-[0_2px_12px_var(--ov-accent-border)]">
              <Sparkles className="w-5 h-5" />
            </div>
            <div>
              <span className="text-lg font-bold text-slate-900 dark:text-slate-50 tracking-tight block font-display">AI Interview</span>
              <span className="ov-label">智能面试助手</span>
            </div>
          </Link>
        </div>

        {/* 主题切换按钮 */}
        <div className="px-4 pb-2 pt-4">
          <button
            onClick={toggleTheme}
            className="btn-secondary w-full flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium"
          >
            {theme === 'dark' ? (
              <>
                <Sun className="w-4 h-4" />
                <span>浅色模式</span>
              </>
            ) : (
              <>
                <Moon className="w-4 h-4" />
                <span>深色模式</span>
              </>
            )}
          </button>
        </div>

        {/* 导航菜单 */}
        <nav className="flex-1 p-4 overflow-y-auto scrollbar-thin">
          <div className="space-y-6">
            {navGroups.map((group) => (
              <div key={group.id}>
                <div className="px-3 mb-2">
                  <span className="ov-label block">{group.title}</span>
                </div>
                <div className="space-y-1">
                  {group.items.map((item) => {
                    const active = isActive(item.path);

                    return (
                      <Link
                        key={item.id}
                        to={item.path}
                        className={`group relative flex items-center gap-3 px-3 py-2.5 rounded-lg border transition-all duration-200
                          ${active
                            ? 'bg-[var(--ov-accent-soft-bg)] border-[var(--ov-accent-border)] text-primary-700 dark:text-primary-300'
                            : 'border-transparent text-slate-600 dark:text-slate-400 hover:bg-[var(--ov-muted)] hover:border-[var(--ov-border-soft)] hover:text-slate-900 dark:hover:text-slate-100'
                          }`}
                      >
                        <div className={`w-8 h-8 rounded-md flex items-center justify-center transition-colors
                          ${active
                            ? 'bg-primary-600 dark:bg-primary-500 text-white'
                            : 'bg-[var(--ov-panel)] border border-[var(--ov-border-soft)] text-slate-500 dark:text-slate-400 group-hover:text-primary-600 dark:group-hover:text-primary-400'
                          }`}
                        >
                          <item.icon className="w-4 h-4" />
                        </div>
                        <div className="flex-1 min-w-0">
                          <span className={`text-sm block ${active ? 'font-semibold' : 'font-medium'}`}>
                            {item.label}
                          </span>
                          {item.description && (
                            <span className="text-xs text-slate-500 dark:text-slate-500 truncate block">
                              {item.description}
                            </span>
                          )}
                        </div>
                        {active && <span className="w-1.5 h-1.5 rounded-full bg-primary-500 dark:bg-primary-400" />}
                      </Link>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        </nav>

        {/* 底部信息 */}
        <div className="p-4 border-t border-[var(--ov-border-soft)]">
          <div className="px-3 py-2.5 rounded-lg bg-[var(--ov-panel)] border border-[var(--ov-border-soft)]">
            <p className="ov-label !text-primary-600 dark:!text-primary-400">AI Interview v1.0</p>
            <p className="text-xs text-slate-500 dark:text-slate-500 mt-1 font-mono">Powered by AI</p>
          </div>
        </div>
      </aside>

      {/* 移动端导航抽屉（复用桌面导航结构，含全部页面入口） */}
      <AnimatePresence>
        {mobileNavOpen && (
          <div className="md:hidden">
            <motion.div
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.15 }}
              onClick={() => setMobileNavOpen(false)}
              className="fixed inset-0 z-50 bg-black/40"
            />
            <motion.aside
              initial={{ x: '-100%' }}
              animate={{ x: 0 }}
              exit={{ x: '-100%' }}
              transition={{ duration: 0.25, type: 'tween' }}
              className="fixed top-0 left-0 z-[60] h-full w-72 max-w-[85vw] bg-[var(--ov-bg-soft)] border-r border-[var(--ov-border-soft)] flex flex-col"
            >
              <div className="h-12 px-4 border-b border-[var(--ov-border-soft)] flex items-center justify-between shrink-0">
                <span className="text-base font-bold text-slate-900 dark:text-white font-display">全部功能</span>
                <button
                  onClick={() => setMobileNavOpen(false)}
                  className="p-2 rounded-lg text-slate-500 dark:text-slate-400 hover:bg-primary-50 hover:text-primary-600 dark:hover:bg-primary-900/30 dark:hover:text-primary-400 transition-colors"
                  title="关闭"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>

              <nav className="flex-1 p-4 overflow-y-auto scrollbar-thin">
                <div className="space-y-6">
                  {navGroups.map((group) => (
                    <div key={group.id}>
                      <div className="px-3 mb-2">
                        <span className="ov-label block">{group.title}</span>
                      </div>
                      <div className="space-y-1">
                        {group.items.map((item) => {
                          const active = isActive(item.path);

                          return (
                            <Link
                              key={item.id}
                              to={item.path}
                              className={`group relative flex items-center gap-3 px-3 py-2.5 rounded-lg border transition-all duration-200
                                ${active
                                  ? 'bg-[var(--ov-accent-soft-bg)] border-[var(--ov-accent-border)] text-primary-700 dark:text-primary-300'
                                  : 'border-transparent text-slate-600 dark:text-slate-400 hover:bg-[var(--ov-muted)] hover:border-[var(--ov-border-soft)] hover:text-slate-900 dark:hover:text-slate-100'
                                }`}
                            >
                              <div className={`w-8 h-8 rounded-md flex items-center justify-center transition-colors
                                ${active
                                  ? 'bg-primary-600 dark:bg-primary-500 text-white'
                                  : 'bg-[var(--ov-panel)] border border-[var(--ov-border-soft)] text-slate-500 dark:text-slate-400 group-hover:text-primary-600 dark:group-hover:text-primary-400'
                                }`}
                              >
                                <item.icon className="w-4 h-4" />
                              </div>
                              <div className="flex-1 min-w-0">
                                <span className={`text-sm block ${active ? 'font-semibold' : 'font-medium'}`}>
                                  {item.label}
                                </span>
                              </div>
                            </Link>
                          );
                        })}
                      </div>
                    </div>
                  ))}
                </div>
              </nav>

              <div className="p-4 border-t border-[var(--ov-border-soft)] shrink-0 space-y-3">
                <button
                  onClick={toggleTheme}
                  className="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-slate-600 dark:text-slate-400 hover:bg-[var(--ov-muted)] transition-colors"
                >
                  <div className="w-8 h-8 rounded-md flex items-center justify-center bg-[var(--ov-panel)] border border-[var(--ov-border-soft)] text-slate-500 dark:text-slate-400">
                    {theme === 'dark' ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
                  </div>
                  <span>{theme === 'dark' ? '浅色模式' : '深色模式'}</span>
                </button>
                <p className="text-xs text-slate-400 dark:text-slate-500 font-mono px-1">AI Interview v1.0 · Powered by AI</p>
              </div>
            </motion.aside>
          </div>
        )}
      </AnimatePresence>

      {/* 主内容区 */}
      <main className="flex-1 md:ml-64 px-4 pt-3 pb-6 min-h-[calc(100dvh-3rem)] md:p-10 md:min-h-screen">
        <MobileTopBarActionContext.Provider value={registerMobileTopBarAction}>
          <motion.div
            key={currentPath}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -20 }}
            transition={{ duration: 0.3 }}
          >
            <Outlet context={{ openInterviewModalWithResume, openInterviewModal }} />
          </motion.div>
        </MobileTopBarActionContext.Provider>
      </main>

      {/* 统一面试弹窗 */}
      <UnifiedInterviewModal
        isOpen={interviewModalPreset !== null}
        onClose={() => setInterviewModalPreset(null)}
        onStart={handleInterviewStart}
        defaultMode={interviewModalPreset?.defaultMode || 'text'}
        defaultResumeId={interviewModalPreset?.defaultResumeId}
        hideModeSwitch={interviewModalPreset?.hideModeSwitch ?? (interviewModalPreset?.defaultResumeId == null)}
        title={interviewModalPreset?.title || '开始模拟面试'}
        subtitle={interviewModalPreset?.subtitle || '选择面试模式和主题，快速开始'}
        startButtonText={interviewModalPreset?.startButtonText || '开始面试'}
      />
    </div>
  );
}

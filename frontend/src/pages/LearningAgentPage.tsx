import {useEffect, useRef, useState, useTransition} from 'react';
import {AnimatePresence, motion} from 'framer-motion';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {Virtuoso, type VirtuosoHandle} from 'react-virtuoso';
import {useNavigate} from 'react-router-dom';
import {ragChatApi, type RagChatSessionListItem} from '../api/ragChat';
import {learningAgentApi} from '../api/learningAgent';
import {userApi} from '../api/user';
import {getStoredUser} from '../utils/currentUser';
import type {AgentStep, AskLearnerPayload} from '../types/learning';
import type {UserProfile} from '../types/user';
import {formatDateOnly} from '../utils/date';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import CodeBlock from '../components/CodeBlock';
import ToolStepsPanel from '../components/learning/ToolStepsPanel';
import UserProfileModal from '../components/UserProfileModal';
import {
  Brain,
  Check,
  CircleHelp,
  Edit,
  MessageSquare,
  Pin,
  Plus,
  Trash2,
} from 'lucide-react';

interface LearningAgentPageProps {
  onBack: () => void;
  onUpload: () => void;
}

/** askLearner 提问卡片：answer 为学员点选结果，closed 表示已超时/会话结束 */
interface AskCardState extends AskLearnerPayload {
  answer?: string;
  closed?: boolean;
}

interface Message {
  id?: number;
  type: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  reasoning?: string;
  steps?: AgentStep[];
  asks?: AskCardState[];
}

const SUGGESTIONS = [
  '帮我制定一个学习计划',
  '考考我最近学过的知识点',
  '用我学过的知识解释一个新概念',
  '总结一下我的薄弱环节',
];

export default function LearningAgentPage({onBack, onUpload}: LearningAgentPageProps) {
  const navigate = useNavigate();

  // 会话状态
  const [sessions, setSessions] = useState<RagChatSessionListItem[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<number | null>(null);
  const [currentSessionTitle, setCurrentSessionTitle] = useState<string>('');
  const [loadingSessions, setLoadingSessions] = useState(false);
  const [sessionDeleteConfirm, setSessionDeleteConfirm] = useState<{ id: number; title: string } | null>(null);
  const [editingSessionTitle, setEditingSessionTitle] = useState<{ id: number; title: string } | null>(null);
  const [newSessionTitle, setNewSessionTitle] = useState('');

  // 消息状态
  const [question, setQuestion] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(false);

  // 当前成员
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [profileModalOpen, setProfileModalOpen] = useState(false);

  // refs
  const virtuosoRef = useRef<VirtuosoHandle>(null);
  const rafRef = useRef<number>();

  const [, startTransition] = useTransition();

  useEffect(() => {
    loadSessions();
    loadProfile();
  }, []);

  const loadSessions = async () => {
    setLoadingSessions(true);
    try {
      const list = await ragChatApi.listSessions();
      setSessions(list);
    } catch (err) {
      console.error('加载会话列表失败', err);
    } finally {
      setLoadingSessions(false);
    }
  };

  const loadProfile = async () => {
    const stored = getStoredUser();
    if (!stored) {
      return;
    }
    try {
      const fresh = await userApi.get(stored.id);
      setProfile(fresh);
    } catch (err) {
      console.error('加载学习资料失败', err);
    }
  };

  const handleNewSession = () => {
    setCurrentSessionId(null);
    setCurrentSessionTitle('');
    setMessages([]);
  };

  const handleLoadSession = async (sessionId: number) => {
    try {
      const detail = await ragChatApi.getSessionDetail(sessionId);
      setCurrentSessionId(detail.id);
      setCurrentSessionTitle(detail.title);
      setMessages(detail.messages.map((m) => ({
        id: m.id,
        type: m.type,
        content: m.content,
        timestamp: new Date(m.createdAt),
        steps: parseSteps(m.toolSteps),
      })));
    } catch (err) {
      console.error('加载会话失败', err);
    }
  };

  const parseSteps = (raw?: string | null): AgentStep[] => {
    if (!raw) {
      return [];
    }
    try {
      const parsed = JSON.parse(raw) as AgentStep[];
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  };

  const handleDeleteSession = async () => {
    if (!sessionDeleteConfirm) return;
    try {
      await ragChatApi.deleteSession(sessionDeleteConfirm.id);
      await loadSessions();
      if (currentSessionId === sessionDeleteConfirm.id) {
        handleNewSession();
      }
      setSessionDeleteConfirm(null);
    } catch (err) {
      console.error('删除会话失败', err);
    }
  };

  const handleEditSessionTitle = (sessionId: number, title: string) => {
    setEditingSessionTitle({id: sessionId, title});
    setNewSessionTitle(title);
  };

  const handleSaveSessionTitle = async () => {
    if (!editingSessionTitle || !newSessionTitle.trim()) return;
    try {
      await ragChatApi.updateSessionTitle(editingSessionTitle.id, newSessionTitle.trim());
      await loadSessions();
      if (currentSessionId === editingSessionTitle.id) {
        setCurrentSessionTitle(newSessionTitle.trim());
      }
      setEditingSessionTitle(null);
      setNewSessionTitle('');
    } catch (err) {
      console.error('更新会话标题失败', err);
    }
  };

  const handleTogglePin = async (sessionId: number, e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await ragChatApi.togglePin(sessionId);
      await loadSessions();
    } catch (err) {
      console.error('切换置顶状态失败', err);
    }
  };

  const formatMarkdown = (text: string): string => {
    if (!text) return '';
    return text
      .replace(/\\n/g, '\n')
      .replace(/^(#{1,6})([^\s#\n])/gm, '$1 $2')
      .replace(/^(\s*)(\d+)\.([^\s\n])/gm, '$1$2. $3')
      .replace(/^(\s*[-*])([^\s\n-])/gm, '$1 $2')
      .replace(/\n{3,}/g, '\n\n');
  };

  const updateLastAssistant = (updater: (msg: Message) => Message) => {
    setMessages((prev) => {
      const next = [...prev];
      const lastIndex = next.length - 1;
      if (lastIndex >= 0 && next[lastIndex].type === 'assistant') {
        next[lastIndex] = updater(next[lastIndex]);
      }
      return next;
    });
  };

  const handleSubmitQuestion = async (preset?: string) => {
    const raw = preset ?? question;
    if (!raw.trim() || loading) return;

    const userQuestion = raw.trim();
    setQuestion('');
    setLoading(true);

    let sessionId = currentSessionId;
    if (!sessionId) {
      try {
        const session = await learningAgentApi.createSession();
        sessionId = session.id;
        setCurrentSessionId(sessionId);
        setCurrentSessionTitle(session.title);
      } catch (err) {
        console.error('创建会话失败', err);
        setLoading(false);
        return;
      }
    }

    setMessages((prev) => [...prev,
      {type: 'user', content: userQuestion, timestamp: new Date()},
      {type: 'assistant', content: '', timestamp: new Date(), steps: []},
    ]);

    let fullContent = '';
    let fullReasoning = '';

    try {
      await learningAgentApi.streamChat(sessionId, userQuestion, {
        onReasoning: (text) => {
          fullReasoning += fullReasoning ? `\n\n${text}` : text;
          if (rafRef.current) {
            cancelAnimationFrame(rafRef.current);
          }
          rafRef.current = requestAnimationFrame(() => {
            startTransition(() => {
              updateLastAssistant((msg) => ({...msg, reasoning: fullReasoning}));
            });
          });
        },
        onStep: (step) => {
          startTransition(() => {
            updateLastAssistant((msg) => ({
              ...msg,
              steps: [...(msg.steps ?? []), step],
            }));
          });
        },
        onDelta: (text) => {
          fullContent += text;
          if (rafRef.current) {
            cancelAnimationFrame(rafRef.current);
          }
          rafRef.current = requestAnimationFrame(() => {
            startTransition(() => {
              updateLastAssistant((msg) => ({...msg, content: fullContent}));
            });
          });
        },
        onAsk: (payload) => {
          startTransition(() => {
            updateLastAssistant((msg) => ({
              ...msg,
              asks: [...(msg.asks ?? []), {...payload}],
            }));
          });
        },
        onComplete: () => {
          // 流结束：还没被回答的提问卡片按超时关闭（Agent 已按超时降级继续）
          startTransition(() => {
            updateLastAssistant((msg) => ({
              ...msg,
              asks: (msg.asks ?? []).map((a) => (a.answer ? a : {...a, closed: true})),
            }));
          });
          setLoading(false);
          loadSessions();
        },
        onTitle: (title) => {
          // 首轮回答结束后后端自动生成的标题（仅占位标题会被替换）
          setCurrentSessionTitle(title);
        },
        onError: (error: Error) => {
          console.error('学习帮手回答失败:', error);
          updateLastAssistant((msg) => ({
            ...msg,
            content: fullContent || `回答失败：${error.message || '请重试'}`,
          }));
          setLoading(false);
        },
      });
    } catch (err) {
      console.error('发起流式请求失败:', err);
      updateLastAssistant((msg) => ({
        ...msg,
        content: fullContent || (err instanceof Error ? err.message : '回答失败，请重试'),
      }));
      setLoading(false);
    }
  };

  const formatTimeAgo = (dateStr: string): string => {
    const date = new Date(dateStr);
    const now = new Date();
    const diff = now.getTime() - date.getTime();
    const minutes = Math.floor(diff / 60000);
    const hours = Math.floor(diff / 3600000);
    const days = Math.floor(diff / 86400000);

    if (minutes < 1) return '刚刚';
    if (minutes < 60) return `${minutes} 分钟前`;
    if (hours < 24) return `${hours} 小时前`;
    if (days < 7) return `${days} 天前`;
    return formatDateOnly(dateStr);
  };

  // 学员点选提问选项：提交后端放行 Agent 等待，卡片记住所选
  const handleAnswerAsk = async (msg: Message, askIndex: number, answer: string) => {
    if (!currentSessionId) return;
    const applyState = (patch: Partial<AskCardState>) => {
      startTransition(() => {
        setMessages((prev) => prev.map((m) => {
          if (m !== msg) return m;
          return {
            ...m,
            asks: (m.asks ?? []).map((a, i) => (i === askIndex ? {...a, ...patch} : a)),
          };
        }));
      });
    };
    applyState({answer});
    try {
      await learningAgentApi.answerAsk(currentSessionId, answer);
    } catch (err) {
      console.error('提交回答失败', err);
      applyState({answer: undefined, closed: true});
    }
  };

  // Agent 的选择提问卡片：等价于成熟 Agent 的"选项弹窗"
  const renderAskCards = (msg: Message) => {
    if (!msg.asks || msg.asks.length === 0) {
      return null;
    }
    return (
      <div className="space-y-2.5 mb-3">
        {msg.asks.map((ask, askIndex) => {
          const answered = !!ask.answer;
          return (
            <div
              key={askIndex}
              className="rounded-xl border border-primary-100 dark:border-primary-900/50 bg-primary-50/60 dark:bg-primary-900/20 p-3"
            >
              <div className="flex items-start gap-2 mb-2.5">
                <CircleHelp className="w-4 h-4 text-primary-500 mt-0.5 flex-shrink-0"/>
                <p className="text-sm font-medium text-slate-700 dark:text-slate-200">{ask.question}</p>
              </div>
              <div className="flex flex-col gap-1.5">
                {ask.options.map((option) => (
                  <button
                    key={option}
                    disabled={answered || ask.closed}
                    onClick={() => handleAnswerAsk(msg, askIndex, option)}
                    className={`text-left text-sm px-3 py-2 rounded-lg border transition-all ${
                      answered && ask.answer === option
                        ? 'border-primary-500 bg-primary-500 text-white'
                        : answered || ask.closed
                          ? 'border-slate-200 dark:border-slate-600 text-slate-400 dark:text-slate-500 cursor-not-allowed'
                          : 'border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:border-primary-400 hover:text-primary-600 dark:hover:text-primary-400 bg-white dark:bg-slate-800'
                    }`}
                  >
                    {option}
                  </button>
                ))}
              </div>
              {answered ? (
                <p className="mt-2 text-xs text-primary-500 flex items-center gap-1">
                  <Check className="w-3.5 h-3.5"/>
                  已选择「{ask.answer}」
                </p>
              ) : ask.closed ? (
                <p className="mt-2 text-xs text-slate-400 dark:text-slate-500">未回答，AI 已按自己的判断继续</p>
              ) : (
                <p className="mt-2 text-xs text-slate-400 dark:text-slate-500 animate-pulse">等待你的选择…</p>
              )}
            </div>
          );
        })}
      </div>
    );
  };

  // 思维链折叠块：思考中默认展开，答案开始输出后自动收起
  const renderReasoning = (msg: Message, index: number) => {
    if (!msg.reasoning) {
      return null;
    }
    const isThinking = loading && index === messages.length - 1 && !msg.content;
    return (
      <details className="mb-3" open={isThinking}>
        <summary className="cursor-pointer select-none list-none inline-flex items-center gap-1.5 text-xs text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300">
          <Brain className={`w-3.5 h-3.5 ${isThinking ? 'animate-pulse text-primary-500' : ''}`}/>
          {isThinking ? '思考中…' : '已深度思考'}
        </summary>
        <div className="mt-2 px-3 py-2 rounded-xl bg-slate-50 dark:bg-slate-700/60 text-xs leading-relaxed text-slate-500 dark:text-slate-400 whitespace-pre-wrap max-h-60 overflow-y-auto">
          {msg.reasoning}
        </div>
      </details>
    );
  };

  return (
    <div className="max-w-7xl mx-auto pt-8 pb-10 px-4">
      {/* 头部 */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white mb-1">学习帮手</h1>
          <p className="text-slate-500 dark:text-slate-400 text-sm">
            和 AI 一起学习，自动检索知识库，学过的知识点自动记入台账
          </p>
        </div>
        <div className="flex gap-3">
          <motion.button
            onClick={() => navigate('/learning/plan')}
            className="px-4 py-2 border border-slate-200 dark:border-slate-600 rounded-xl text-slate-600 dark:text-slate-300 font-medium hover:bg-slate-50 dark:hover:bg-slate-700 transition-all text-sm"
            whileHover={{scale: 1.02}}
            whileTap={{scale: 0.98}}
          >
            学习计划
          </motion.button>
          <motion.button
            onClick={() => navigate('/learning/records')}
            className="px-4 py-2 border border-slate-200 dark:border-slate-600 rounded-xl text-slate-600 dark:text-slate-300 font-medium hover:bg-slate-50 dark:hover:bg-slate-700 transition-all text-sm"
            whileHover={{scale: 1.02}}
            whileTap={{scale: 0.98}}
          >
            学习台账
          </motion.button>
          <motion.button
            onClick={onUpload}
            className="px-4 py-2 border border-slate-200 dark:border-slate-600 rounded-xl text-slate-600 dark:text-slate-300 font-medium hover:bg-slate-50 dark:hover:bg-slate-700 transition-all text-sm"
            whileHover={{scale: 1.02}}
            whileTap={{scale: 0.98}}
          >
            上传知识库
          </motion.button>
          <motion.button
            onClick={onBack}
            className="px-4 py-2 border border-slate-200 dark:border-slate-600 rounded-xl text-slate-600 dark:text-slate-300 font-medium hover:bg-slate-50 dark:hover:bg-slate-700 transition-all text-sm"
            whileHover={{scale: 1.02}}
            whileTap={{scale: 0.98}}
          >
            返回
          </motion.button>
        </div>
      </div>

      <div className="flex gap-4 h-[calc(100vh-10rem)]">
        {/* 左侧：对话历史 */}
        <div className="w-64 flex-shrink-0">
          <div className="bg-white dark:bg-slate-800 rounded-2xl p-4 shadow-sm h-full flex flex-col border border-slate-100 dark:border-slate-700">
            <div className="flex items-center justify-between mb-4">
              <h2 className="text-base font-semibold text-slate-800 dark:text-white">对话历史</h2>
              <motion.button
                onClick={handleNewSession}
                className="p-1.5 text-primary-500 hover:bg-primary-50 dark:hover:bg-primary-900/30 rounded-lg transition-colors"
                whileHover={{scale: 1.05}}
                whileTap={{scale: 0.95}}
                title="新建对话"
              >
                <Plus className="w-5 h-5"/>
              </motion.button>
            </div>

            <div className="flex-1 overflow-y-auto">
              {loadingSessions ? (
                <div className="text-center py-6">
                  <motion.div
                    className="w-5 h-5 border-2 border-primary-500 border-t-transparent rounded-full mx-auto"
                    animate={{rotate: 360}}
                    transition={{duration: 1, repeat: Infinity, ease: 'linear'}}
                  />
                </div>
              ) : sessions.length === 0 ? (
                <div className="text-center py-6 text-slate-400 dark:text-slate-500 text-sm">
                  暂无对话历史
                </div>
              ) : (
                <div className="space-y-2">
                  {sessions.map((session) => (
                    <div
                      key={session.id}
                      onClick={() => handleLoadSession(session.id)}
                      className={`p-3 rounded-lg cursor-pointer transition-all group ${currentSessionId === session.id
                        ? 'bg-primary-50 dark:bg-primary-900/30 border border-primary-500'
                        : 'bg-slate-50 dark:bg-slate-700/50 hover:bg-slate-100 dark:hover:bg-slate-700 border border-transparent'
                      } ${session.isPinned ? 'border-l-4 border-l-primary-500' : ''}`}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-1.5">
                            {session.isPinned && (
                              <Pin className="w-3.5 h-3.5 text-primary-500 fill-primary-500 flex-shrink-0"/>
                            )}
                            <p className="font-medium text-slate-800 dark:text-white text-sm truncate">{session.title}</p>
                          </div>
                          <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
                            {session.messageCount} 条消息 · {formatTimeAgo(session.updatedAt)}
                          </p>
                        </div>
                        <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-all">
                          <button
                            onClick={(e) => handleTogglePin(session.id, e)}
                            className={`p-1 rounded transition-colors ${session.isPinned
                              ? 'text-primary-500 hover:text-primary-600'
                              : 'text-slate-400 hover:text-primary-500'
                            }`}
                            title={session.isPinned ? '取消置顶' : '置顶'}
                          >
                            <Pin className={`w-4 h-4 ${session.isPinned ? 'fill-primary-500' : ''}`}/>
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              handleEditSessionTitle(session.id, session.title);
                            }}
                            className="p-1 text-slate-400 hover:text-primary-500 rounded transition-colors"
                            title="编辑标题"
                          >
                            <Edit className="w-4 h-4"/>
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setSessionDeleteConfirm({id: session.id, title: session.title});
                            }}
                            className="p-1 text-slate-400 hover:text-red-500 rounded transition-colors"
                            title="删除"
                          >
                            <Trash2 className="w-4 h-4"/>
                          </button>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* 中间：聊天区域 */}
        <div className="flex-1 min-w-0">
          <div className="bg-white dark:bg-slate-800 rounded-2xl shadow-sm flex flex-col h-full border border-slate-100 dark:border-slate-700">
            {/* 会话头部：学习成员是主体，会话标题为附属信息 */}
            <div className="flex items-center gap-3 p-4 border-b border-slate-200 dark:border-slate-600">
              <button
                onClick={() => setProfileModalOpen(true)}
                className="group flex items-center gap-3 flex-shrink-0"
                title="编辑我的学习资料"
              >
                <span className="w-10 h-10 rounded-xl bg-primary-600/10 dark:bg-primary-400/15 ring-1 ring-primary-600/20 dark:ring-primary-400/30 flex items-center justify-center text-xl leading-none group-hover:ring-primary-500/50 transition-all">
                  {profile?.avatarEmoji || '🙂'}
                </span>
                <span className="text-left">
                  <span className="flex items-center gap-1.5">
                    <span className="text-base font-semibold text-slate-900 dark:text-slate-50 truncate max-w-32">
                      {profile?.nickname || '学员'}
                    </span>
                    <Edit className="w-3.5 h-3.5 text-slate-400 opacity-0 group-hover:opacity-100 transition-opacity flex-shrink-0"/>
                  </span>
                  <span className="block text-xs text-slate-400 dark:text-slate-500">学习成员 · 点击编辑资料</span>
                </span>
              </button>

              <div className="h-8 w-px bg-slate-200 dark:bg-slate-700 flex-shrink-0"/>

              <button
                onClick={() => currentSessionId && handleEditSessionTitle(currentSessionId, currentSessionTitle)}
                disabled={!currentSessionId}
                className="group/title flex-1 min-w-0 flex items-center gap-1.5 text-left disabled:cursor-default"
                title={currentSessionId ? '重命名对话' : undefined}
              >
                <span className="truncate text-sm text-slate-500 dark:text-slate-400 group-hover/title:text-primary-600 dark:group-hover/title:text-primary-400 transition-colors">
                  {currentSessionTitle || '新的学习对话'}
                </span>
                {currentSessionId && (
                  <Edit className="w-3.5 h-3.5 text-slate-400 opacity-0 group-hover/title:opacity-100 transition-opacity flex-shrink-0"/>
                )}
              </button>
            </div>

            {/* 消息列表 */}
            <div className="flex-1 min-h-0 relative dark:bg-slate-800">
              {messages.length === 0 ? (
                <div className="absolute inset-0 flex flex-col items-center justify-center px-6">
                  <MessageSquare className="w-12 h-12 mx-auto mb-3 text-slate-400 dark:text-slate-500 opacity-50"/>
                  <p className="text-sm text-slate-500 dark:text-slate-400 mb-1">
                    {profile ? `${profile.nickname}，今天想学点什么？` : '今天想学点什么？'}
                  </p>
                  <p className="text-xs text-slate-400 dark:text-slate-500 mb-6">
                    AI 会自动检索知识库，学过的知识点会记入你的学习台账
                  </p>
                  <div className="flex flex-wrap justify-center gap-2 max-w-xl">
                    {SUGGESTIONS.map((suggestion) => (
                      <button
                        key={suggestion}
                        onClick={() => handleSubmitQuestion(suggestion)}
                        className="px-3 py-1.5 text-xs bg-slate-50 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded-full hover:bg-primary-50 dark:hover:bg-primary-900/30 hover:text-primary-600 dark:hover:text-primary-400 transition-colors"
                      >
                        {suggestion}
                      </button>
                    ))}
                  </div>
                </div>
              ) : (
                <Virtuoso
                  ref={virtuosoRef}
                  data={messages}
                  initialTopMostItemIndex={messages.length - 1}
                  followOutput="smooth"
                  className="h-full w-full"
                  itemContent={(index, msg) => (
                    <div className="pb-4 px-4 first:pt-4 dark:bg-slate-800">
                      <motion.div
                        initial={{opacity: 0, y: 10}}
                        animate={{opacity: 1, y: 0}}
                        className={`flex ${msg.type === 'user' ? 'justify-end' : 'justify-start'}`}
                      >
                        <div
                          className={`max-w-[85%] rounded-2xl p-4 shadow-sm ${msg.type === 'user'
                            ? 'bg-primary-600 text-white'
                            : 'bg-white dark:bg-slate-800 border border-slate-100 dark:border-slate-600 text-slate-800 dark:text-slate-100'
                          }`}
                        >
                          {msg.type === 'user' ? (
                            <p className="whitespace-pre-wrap leading-relaxed text-sm">{msg.content}</p>
                          ) : (
                            <div>
                              {renderReasoning(msg, index)}
                              <ToolStepsPanel
                                steps={msg.steps ?? []}
                                running={loading && index === messages.length - 1}
                              />
                              {renderAskCards(msg)}
                              <div className="prose prose-slate dark:prose-invert prose-sm max-w-none">
                                <ReactMarkdown
                                  remarkPlugins={[remarkGfm]}
                                  components={{
                                    code: ({className, children}) => {
                                      const match = /language-(\w+)/.exec(className || '');
                                      const isInline = !match;

                                      if (isInline) {
                                        return (
                                          <code
                                            className="bg-slate-100 dark:bg-slate-600 text-primary-600 dark:text-primary-400 px-1.5 py-0.5 rounded-md text-sm font-normal">
                                            {children}
                                          </code>
                                        );
                                      }

                                      return (
                                        <CodeBlock language={match[1]}>
                                          {String(children).replace(/\n$/, '')}
                                        </CodeBlock>
                                      );
                                    },
                                    pre: ({children}) => <>{children}</>,
                                  }}
                                >
                                  {formatMarkdown(msg.content)}
                                </ReactMarkdown>
                                {loading && index === messages.length - 1 && (
                                  <span className="inline-block w-0.5 h-5 bg-primary-500 ml-1 animate-pulse"/>
                                )}
                              </div>
                            </div>
                          )}
                        </div>
                      </motion.div>
                    </div>
                  )}
                />
              )}
            </div>

            {/* 输入区域 */}
            <div className="p-4 border-t border-slate-200 dark:border-slate-600">
              <div className="flex gap-3">
                <input
                  type="text"
                  value={question}
                  onChange={(e) => setQuestion(e.target.value)}
                  onKeyPress={(e) => e.key === 'Enter' && !e.shiftKey && handleSubmitQuestion()}
                  placeholder="问点什么，比如：帮我入门 Redis…"
                  className="flex-1 px-4 py-2.5 border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent text-sm bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
                  disabled={loading}
                />
                <motion.button
                  onClick={() => handleSubmitQuestion()}
                  disabled={!question.trim() || loading}
                  className="px-5 py-2.5 bg-primary-500 text-white rounded-xl font-medium hover:bg-primary-600 transition-all disabled:opacity-50 disabled:cursor-not-allowed text-sm"
                  whileHover={{scale: loading ? 1 : 1.02}}
                  whileTap={{scale: loading ? 1 : 0.98}}
                >
                  发送
                </motion.button>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* 删除会话确认弹窗 */}
      <DeleteConfirmDialog
        open={!!sessionDeleteConfirm}
        item={sessionDeleteConfirm ? {id: 0, title: sessionDeleteConfirm.title} : null}
        itemType="对话"
        onConfirm={handleDeleteSession}
        onCancel={() => setSessionDeleteConfirm(null)}
      />

      {/* 编辑会话标题弹窗 */}
      <AnimatePresence>
        {editingSessionTitle && (
          <>
            <motion.div
              initial={{opacity: 0}}
              animate={{opacity: 1}}
              exit={{opacity: 0}}
              onClick={() => {
                setEditingSessionTitle(null);
                setNewSessionTitle('');
              }}
              className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50"
            />
            <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
              <motion.div
                initial={{opacity: 0, scale: 0.95, y: 20}}
                animate={{opacity: 1, scale: 1, y: 0}}
                exit={{opacity: 0, scale: 0.95, y: 20}}
                onClick={(e) => e.stopPropagation()}
                className="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl max-w-md w-full p-6 border border-slate-100 dark:border-slate-700"
              >
                <h3 className="text-xl font-bold text-slate-900 dark:text-white mb-4">编辑标题</h3>
                <input
                  type="text"
                  value={newSessionTitle}
                  onChange={(e) => setNewSessionTitle(e.target.value)}
                  onKeyPress={(e) => e.key === 'Enter' && handleSaveSessionTitle()}
                  placeholder="请输入新标题"
                  className="w-full px-4 py-3 text-sm border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 mb-4 bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
                  autoFocus
                />
                <div className="flex justify-end gap-3">
                  <button
                    onClick={() => {
                      setEditingSessionTitle(null);
                      setNewSessionTitle('');
                    }}
                    className="px-4 py-2 text-sm text-slate-600 dark:text-slate-400 hover:text-slate-800 dark:hover:text-white"
                  >
                    取消
                  </button>
                  <button
                    onClick={handleSaveSessionTitle}
                    disabled={!newSessionTitle.trim()}
                    className="px-4 py-2 text-sm bg-primary-500 text-white rounded-lg hover:bg-primary-600 disabled:opacity-50"
                  >
                    保存
                  </button>
                </div>
              </motion.div>
            </div>
          </>
        )}
      </AnimatePresence>

      {/* 学习资料编辑弹窗 */}
      <UserProfileModal
        open={profileModalOpen}
        mode="edit"
        initial={profile}
        onClose={() => setProfileModalOpen(false)}
        onSaved={(saved) => {
          setProfile(saved);
          setProfileModalOpen(false);
        }}
      />
    </div>
  );
}

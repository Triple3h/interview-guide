import {Fragment, useEffect, useRef, useState, useTransition} from 'react';
import {AnimatePresence, motion} from 'framer-motion';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {Virtuoso, type VirtuosoHandle} from 'react-virtuoso';
import {useNavigate} from 'react-router-dom';
import {ragChatApi, type RagChatSessionListItem} from '../api/ragChat';
import {learningAgentApi} from '../api/learningAgent';
import {userApi} from '../api/user';
import {getStoredUser, storeUser} from '../utils/currentUser';
import type {AgentBlock, AgentStep, AskLearnerPayload, ToolInvocation} from '../types/learning';
import type {UserProfile} from '../types/user';
import {formatDateOnly} from '../utils/date';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import CodeBlock from '../components/CodeBlock';
import UserMenu from '../components/UserMenu';
import {groupInvocations, ReasoningBlock, ToolCallBlock} from '../components/learning/AgentBlocks';
import {useMobileTopBarAction} from '../hooks/useMobileTopBarAction';
import {
  ArrowLeft,
  CalendarCheck,
  Check,
  CircleHelp,
  Edit,
  History,
  MessageSquare,
  NotebookPen,
  Pin,
  Plus,
  Trash2,
  Upload,
} from 'lucide-react';

interface LearningAgentPageProps {
  onBack: () => void;
  onUpload: () => void;
}

/** askLearner 提问卡片：answer 为学员点选结果，closed 表示已超时/会话结束 */
interface AskCardState extends AskLearnerPayload {
  /** 会话内自增 id，弹窗与正文回答框按它定位卡片 */
  askId: number;
  answer?: string;
  closed?: boolean;
}

/** 时间线块：思考 / 工具调用 / 正文 / 提问卡片，按发生顺序渲染 */
type MessageBlock = AgentBlock | { kind: 'ask'; ask: AskCardState };

interface Message {
  id?: number;
  type: 'user' | 'assistant';
  content: string;
  timestamp: Date;
  blocks: MessageBlock[];
}

const SUGGESTIONS = [
  '帮我制定一个学习计划',
  '考考我最近学过的知识点',
  '用我学过的知识解释一个新概念',
  '总结一下我的薄弱环节',
];

/** askLearner 的提问与回答统一由问答卡承载，不再生成工具卡 */
const ASK_TOOL = 'askLearner';
const ASK_QUESTION_PREFIX = '向学员提问';
const ASK_ANSWER_PREFIX = '学员的回答';

/** 去掉与工具标签重复的前缀（后端摘要形如「加载知识基线：AGENT_BASIS」） */
const stripPrefix = (text: string | undefined, label: string): string => {
  const raw = (text ?? '').trim();
  return raw.startsWith(label) ? raw.slice(label.length).replace(/^[:：]\s*/, '') : raw;
};

/** 时间线分片合并：同类分片（思考 / 正文）并进最后一块，否则新起一块 */
const mergeBlock = (blocks: MessageBlock[], block: MessageBlock): MessageBlock[] => {
  const last = blocks[blocks.length - 1];
  if (block.kind === 'reasoning' && last?.kind === 'reasoning') {
    return [...blocks.slice(0, -1), {kind: 'reasoning', text: last.text + block.text}];
  }
  if (block.kind === 'text' && last?.kind === 'text') {
    return [...blocks.slice(0, -1), {kind: 'text', text: last.text + block.text}];
  }
  return [...blocks, block];
};

/** askLearner 的工具步骤还原成问答卡：历史消息只落库工具步骤，回放时也长成同一形态 */
const toAskBlock = (invocation: ToolInvocation, seq: number): MessageBlock => {
  const answer = stripPrefix(invocation.resultSummary, ASK_ANSWER_PREFIX);
  return {
    kind: 'ask',
    ask: {
      // 历史回放的卡片不参与弹窗交互，用负数 id 与实时流的自增 id 区分
      askId: -seq,
      question: stripPrefix(invocation.argsSummary, ASK_QUESTION_PREFIX),
      options: [],
      answer: answer || undefined,
      closed: !answer,
    },
  };
};

/** 回答框：学员点选后插回正文中间，小字问题 + 高亮所选答案 */
function AskAnswerCard({ask}: {ask: AskCardState}) {
  return (
    <div className="my-3 rounded-xl border border-primary-200/70 dark:border-primary-900/60 bg-primary-50/70 dark:bg-primary-900/20 p-3">
      <p className="flex items-start gap-1.5 text-xs leading-relaxed text-slate-400 dark:text-slate-500 mb-1.5">
        <CircleHelp className="w-3.5 h-3.5 mt-0.5 flex-shrink-0"/>
        <span>{ask.question}</span>
      </p>
      <p className="flex items-start gap-1.5 text-sm font-medium leading-relaxed text-primary-600 dark:text-primary-400">
        <Check className="w-4 h-4 mt-0.5 flex-shrink-0"/>
        <span>{ask.answer}</span>
      </p>
    </div>
  );
}

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
  /** 移动端：对话历史抽屉是否展开 */
  const [mobileSessionsOpen, setMobileSessionsOpen] = useState(false);

  // 消息状态
  const [question, setQuestion] = useState('');
  const [messages, setMessages] = useState<Message[]>([]);
  const [loading, setLoading] = useState(false);
  /** 等待学员回答的提问卡片 id：选项弹窗锚在输入框上方 */
  const [pendingAskId, setPendingAskId] = useState<number | null>(null);
  const askSeq = useRef(0);

  // 当前成员
  const [profile, setProfile] = useState<UserProfile | null>(null);

  // refs
  const virtuosoRef = useRef<VirtuosoHandle>(null);

  const [, startTransition] = useTransition();

  // 待回答提问：按 id 从消息里派生，回答/超时后弹窗自动消失
  const pendingAsk = (() => {
    if (pendingAskId == null) {
      return null;
    }
    for (const m of messages) {
      for (const block of m.blocks) {
        if (block.kind === 'ask' && block.ask.askId === pendingAskId) {
          return block.ask;
        }
      }
    }
    return null;
  })();
  const askPopupOpen = !!pendingAsk && !pendingAsk.answer && !pendingAsk.closed;

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
    setMobileSessionsOpen(false);
  };

  // 移动端顶栏「+」：新建对话
  useMobileTopBarAction('新建对话', handleNewSession);

  // 切换学习成员（含新建后自动进入）：本地身份先生效（请求层实时读），再按新成员重载本页
  const handleSwitchMember = (user: UserProfile) => {
    storeUser(user);
    setProfile(user);
    handleNewSession();
    loadSessions();
  };

  const handleLoadSession = async (sessionId: number) => {
    setMobileSessionsOpen(false);
    try {
      const detail = await ragChatApi.getSessionDetail(sessionId);
      setCurrentSessionId(detail.id);
      setCurrentSessionTitle(detail.title);
      setMessages(detail.messages.map((m) => {
        const timeline = parseTimeline(m.timeline);
        return {
          id: m.id,
          type: m.type,
          content: m.content,
          timestamp: new Date(m.createdAt),
          // 新消息按落库的时间线原顺序回放（思考 → 工具 → 正文）；旧消息退化为「工具卡 + 正文」
          blocks: timeline.length > 0 ? timeline : [
            ...groupInvocations(parseSteps(m.toolSteps))
              .flatMap((invocation, i): MessageBlock[] => (
                invocation.tool === ASK_TOOL ? [toAskBlock(invocation, i + 1)] : [{kind: 'tool', invocation}]
              )),
            ...(m.content ? [{kind: 'text', text: m.content} as MessageBlock] : []),
          ],
        };
      }));
    } catch (err) {
      console.error('加载会话失败', err);
    }
  };

  // 落库的时间线（与流式块同构）→ 消息块；解析失败或旧消息返回空数组，由调用方退化处理
  const parseTimeline = (raw?: string | null): MessageBlock[] => {
    if (!raw) {
      return [];
    }
    try {
      const parsed = JSON.parse(raw) as Array<{ kind?: string; text?: string; invocation?: ToolInvocation }>;
      if (!Array.isArray(parsed)) {
        return [];
      }
      return parsed.flatMap((item, index): MessageBlock[] => {
        if ((item.kind === 'reasoning' || item.kind === 'text') && item.text) {
          return [{kind: item.kind, text: item.text}];
        }
        if (item.kind === 'tool' && item.invocation) {
          return item.invocation.tool === ASK_TOOL
            ? [toAskBlock(item.invocation, index + 1)]
            : [{kind: 'tool', invocation: item.invocation}];
        }
        return [];
      });
    } catch {
      return [];
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
      // 只给「紧凑列表」补空格：**加粗** / -- 分隔线 / *强调* 都保持原样，只有 *项 / -项 才补
      .replace(/^(\s*)([-*])([^\s\n*-])([^\n]*)$/gm, (line, indent: string, marker: string, first: string, rest: string) => (
        marker === '*' && rest.includes('*') ? line : `${indent}${marker} ${first}${rest}`
      ))
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

  // 工具步骤：start 新建卡片，end / error 回填最近一个同名且仍在执行的调用
  const applyToolStep = (step: AgentStep) => {
    // 提问/回答由问答卡承载，不再生成工具卡，避免同一问题在时间线上出现两遍
    if (step.tool === ASK_TOOL) {
      return;
    }
    updateLastAssistant((msg) => {
      const blocks = msg.blocks;
      if (step.phase === 'start') {
        return {...msg, blocks: [...blocks, {
          kind: 'tool',
          invocation: {tool: step.tool, argsSummary: step.summary, status: 'running', resultSummary: ''},
        }]};
      }
      let index = -1;
      for (let i = blocks.length - 1; i >= 0; i -= 1) {
        const block = blocks[i];
        if (block.kind === 'tool' && block.invocation.tool === step.tool && block.invocation.status === 'running') {
          index = i;
          break;
        }
      }
      if (index < 0) {
        return msg;
      }
      const target = blocks[index];
      if (target.kind !== 'tool') {
        return msg;
      }
      const next = [...blocks];
      next[index] = {
        kind: 'tool',
        invocation: {
          ...target.invocation,
          status: step.phase === 'error' ? 'error' : 'ok',
          resultSummary: step.summary,
          detail: step.detail ?? target.invocation.detail,
        },
      };
      return {...msg, blocks: next};
    });
  };

  // 关闭未回答的提问卡片（流结束或失败时）
  const closePendingAsks = (blocks: MessageBlock[]): MessageBlock[] => blocks.map((block) => (
    block.kind === 'ask' && !block.ask.answer ? {...block, ask: {...block.ask, closed: true}} : block
  ));

  const handleSubmitQuestion = async (preset?: string) => {
    const raw = (preset ?? question).trim();

    // 有待回答提问时，输入框提交的是该题的自定义回答（等价于选项之外的"其他"）
    if (pendingAsk && !pendingAsk.answer && !pendingAsk.closed) {
      if (!raw) return;
      setQuestion('');
      await handleAnswerAsk(pendingAsk.askId, raw);
      return;
    }

    if (!raw || loading) return;

    const userQuestion = raw;
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
      {type: 'user', content: userQuestion, timestamp: new Date(), blocks: []},
      {type: 'assistant', content: '', timestamp: new Date(), blocks: []},
    ]);

    let fullContent = '';
    // Agent 在本轮补充过学员档案时，流结束后刷新成员资料，让页头与资料弹窗显示最新值
    let profileUpdated = false;

    // SSE 分片远密于帧率：先把分片攒进队列，每帧批量合并进时间线（一次 setState）。
    // 注意不能用「取消上一帧」的方式节流——同一帧内到达的其余分片会被一起取消，正文会残缺
    let pendingChunks: Array<{ kind: 'reasoning' | 'text'; text: string }> = [];
    let flushRaf: number | null = null;
    let streamClosed = false;

    const flushPendingChunks = () => {
      if (flushRaf != null) {
        cancelAnimationFrame(flushRaf);
        flushRaf = null;
      }
      if (pendingChunks.length === 0) {
        return;
      }
      const batch = pendingChunks;
      pendingChunks = [];
      startTransition(() => {
        updateLastAssistant((msg) => ({
          ...msg,
          blocks: batch.reduce(mergeBlock, msg.blocks),
          content: fullContent,
        }));
      });
    };

    const enqueueChunk = (chunk: { kind: 'reasoning' | 'text'; text: string }) => {
      if (streamClosed) {
        return;
      }
      pendingChunks.push(chunk);
      if (flushRaf == null) {
        flushRaf = requestAnimationFrame(() => {
          flushRaf = null;
          flushPendingChunks();
        });
      }
    };

    try {
      await learningAgentApi.streamChat(sessionId, userQuestion, {
        // 后端按分片推送思维链与正文，前端缓冲后按帧合并（同一段思考/正文合并进同一块）
        onReasoning: (text) => enqueueChunk({kind: 'reasoning', text}),
        onStep: (step) => {
          if (step.tool === 'updateLearnerProfile' && step.phase === 'end') {
            profileUpdated = true;
          }
          // 工具卡必须紧跟其前的思考/正文，先冲刷缓冲再插入，避免顺序错位
          flushPendingChunks();
          startTransition(() => applyToolStep(step));
        },
        onDelta: (text) => {
          fullContent += text;
          enqueueChunk({kind: 'text', text});
        },
        onAsk: (payload) => {
          const askId = ++askSeq.current;
          flushPendingChunks();
          startTransition(() => {
            updateLastAssistant((msg) => ({
              ...msg,
              blocks: [...msg.blocks, {kind: 'ask', ask: {...payload, askId}}],
            }));
          });
          setPendingAskId(askId);
        },
        onComplete: () => {
          streamClosed = true;
          // 流结束前先把缓冲里剩下的分片落进时间线，否则结尾会被吞掉
          flushPendingChunks();
          // 还没被回答的提问卡片按超时关闭（Agent 已按超时降级继续），弹窗随之消失
          startTransition(() => {
            updateLastAssistant((msg) => ({...msg, blocks: closePendingAsks(msg.blocks)}));
          });
          setPendingAskId(null);
          setLoading(false);
          loadSessions();
          if (profileUpdated) loadProfile();
        },
        onTitle: (title) => {
          // 首轮回答结束后后端自动生成的标题（仅占位标题会被替换）
          setCurrentSessionTitle(title);
        },
        onError: (error: Error) => {
          console.error('学习帮手回答失败:', error);
          streamClosed = true;
          flushPendingChunks();
          const fallback = fullContent || `回答失败：${error.message || '请重试'}`;
          startTransition(() => {
            updateLastAssistant((msg) => ({
              ...msg,
              content: fallback,
              blocks: [
                ...closePendingAsks(msg.blocks),
                ...(fullContent ? [] : [{kind: 'text', text: fallback} as MessageBlock]),
              ],
            }));
          });
          setPendingAskId(null);
          setLoading(false);
          if (profileUpdated) loadProfile();
        },
      });
    } catch (err) {
      console.error('发起流式请求失败:', err);
      streamClosed = true;
      flushPendingChunks();
      const fallback = fullContent || (err instanceof Error ? err.message : '回答失败，请重试');
      updateLastAssistant((msg) => ({
        ...msg,
        content: fallback,
        blocks: fullContent ? msg.blocks : [...msg.blocks, {kind: 'text', text: fallback} as MessageBlock],
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

  // 学员回答 askLearner 提问：先在卡片上记下所选，再提交后端放行阻塞中的 Agent
  const handleAnswerAsk = async (askId: number, answer: string) => {
    if (!currentSessionId) return;
    const patchAsk = (patch: Partial<AskCardState>) => {
      startTransition(() => {
        setMessages((prev) => prev.map((m) => {
          if (!m.blocks.some((block) => block.kind === 'ask' && block.ask.askId === askId)) return m;
          return {
            ...m,
            blocks: m.blocks.map((block) => (
              block.kind === 'ask' && block.ask.askId === askId
                ? {...block, ask: {...block.ask, ...patch}}
                : block
            )),
          };
        }));
      });
    };
    patchAsk({answer});
    try {
      await learningAgentApi.answerAsk(currentSessionId, answer);
    } catch (err) {
      console.error('提交回答失败', err);
      patchAsk({answer: undefined, closed: true});
    }
  };

  // 弹窗打开时数字键 1-4 快捷点选（输入框打字时不抢占）
  useEffect(() => {
    if (!pendingAsk || pendingAsk.answer || pendingAsk.closed) {
      return;
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) {
        return;
      }
      const index = Number(e.key) - 1;
      if (index >= 0 && index < pendingAsk.options.length) {
        handleAnswerAsk(pendingAsk.askId, pendingAsk.options[index]);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingAsk?.askId, pendingAsk?.answer, pendingAsk?.closed]);

  const renderMarkdownBody = (text: string, streaming: boolean) => (
    // mb-3 与思考块 / 工具卡对齐，保证时间线各段间距一致（prose 自身末元素 margin 已被清零）
    <div className="prose prose-slate dark:prose-invert prose-sm max-w-none mb-3">
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
        {formatMarkdown(text)}
      </ReactMarkdown>
      {streaming && (
        <span className="inline-block w-0.5 h-5 bg-primary-500 ml-1 animate-pulse"/>
      )}
    </div>
  );

  // 时间线渲染：思考 / 工具调用 / 正文 / 提问卡片按发生顺序排列；正在流式推进的最后一块带光标
  const renderAssistantBody = (msg: Message, index: number) => {
    const streaming = loading && index === messages.length - 1;
    const blocks = msg.blocks;
    return (
      // 各块自带 mb-3，最后一块去掉，避免气泡底部多出一段空白
      <div className="[&>*:last-child]:mb-0">
        {blocks.map((block, i) => {
          const isLast = streaming && i === blocks.length - 1;
          if (block.kind === 'reasoning') {
            return <ReasoningBlock key={i} text={block.text} streaming={isLast}/>;
          }
          if (block.kind === 'tool') {
            // 提问/回答由问答卡承载（历史回放已在上游转成问答卡，这里兜底不重复渲染）
            return block.invocation.tool === ASK_TOOL ? null : <ToolCallBlock key={i} invocation={block.invocation}/>;
          }
          if (block.kind === 'text') {
            return <Fragment key={i}>{renderMarkdownBody(block.text, isLast)}</Fragment>;
          }
          // 未回答 / 超时的提问卡片不占正文（弹窗负责交互）
          return block.ask.answer ? <AskAnswerCard key={i} ask={block.ask}/> : null;
        })}
      </div>
    );
  };

  // 页头操作入口：桌面端在页面标题行（带文字），手机端并入会话头部一行（纯图标）
  const headerActions = [
    {key: 'plan', title: '学习计划', icon: CalendarCheck, onClick: () => navigate('/learning/plan')},
    {key: 'records', title: '学习台账', icon: NotebookPen, onClick: () => navigate('/learning/records')},
    {key: 'upload', title: '上传知识库', icon: Upload, onClick: onUpload},
    {key: 'back', title: '返回', icon: ArrowLeft, onClick: onBack},
  ];

  return (
    <div className="max-w-7xl mx-auto -mx-4 -mb-6 flex flex-col h-[calc(100dvh-3.75rem)] md:mx-auto md:pt-8 md:pb-10 md:px-4 md:mb-0 md:h-auto">
      {/* 头部：仅桌面端（手机端不放标题，操作入口并入下方会话头部一行） */}
      <div className="hidden md:flex items-center justify-between gap-2 mb-6 shrink-0">
        <div className="min-w-0">
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white mb-1 truncate">学习帮手</h1>
          <p className="text-base text-slate-500 dark:text-slate-400">
            和 AI 一起学习，自动检索知识库，学过的知识点自动记入台账
          </p>
        </div>
        <div className="flex items-center gap-3 shrink-0">
          {headerActions.map(({key, title, icon: Icon, onClick}) => (
            <motion.button
              key={key}
              onClick={onClick}
              className="inline-flex items-center gap-1.5 px-4 py-2 border border-slate-200 dark:border-slate-600 rounded-xl text-slate-600 dark:text-slate-300 font-medium hover:bg-slate-50 dark:hover:bg-slate-700 transition-all text-sm"
              whileHover={{scale: 1.02}}
              whileTap={{scale: 0.98}}
              title={title}
            >
              <Icon className="w-4 h-4"/>
              <span>{title}</span>
            </motion.button>
          ))}
        </div>
      </div>

      <div className="flex gap-4 flex-1 min-h-0 md:flex-none md:h-[calc(100vh-10rem)]">
        {/* 左侧：对话历史（桌面常驻；移动端为底部抽屉） */}
        {mobileSessionsOpen && (
          <div
            className="fixed inset-0 z-40 bg-black/40 md:hidden"
            onClick={() => setMobileSessionsOpen(false)}
          />
        )}
        <div
          className={`fixed inset-x-0 bottom-0 z-50 h-[70dvh] p-3 transition-transform duration-200 ease-out md:static md:inset-auto md:z-auto md:h-auto md:w-64 md:flex-shrink-0 md:p-0 md:translate-y-0 ${
            mobileSessionsOpen ? 'translate-y-0' : 'translate-y-full'
          }`}
        >
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
                        <div className="flex items-center gap-1 opacity-100 md:opacity-0 md:group-hover:opacity-100 transition-all">
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
          <div className="bg-white dark:bg-slate-800 md:rounded-2xl md:shadow-sm flex flex-col h-full overscroll-y-contain md:border border-slate-100 dark:border-slate-700">
            {/* 会话头部：学习成员是主体（点开头像出成员菜单），会话标题为附属信息 */}
            <div className="p-3 md:p-4 border-b border-slate-200 dark:border-slate-600">
              <div className="flex items-center gap-1 md:gap-3">
                <button
                  onClick={() => setMobileSessionsOpen(true)}
                  className="md:hidden p-2 text-slate-500 dark:text-slate-400 hover:bg-primary-50 hover:text-primary-600 dark:hover:bg-primary-900/30 dark:hover:text-primary-400 rounded-lg transition-colors flex-shrink-0"
                  title="对话历史"
                >
                  <History className="w-5 h-5"/>
                </button>

                <UserMenu
                  current={profile}
                  locked={loading}
                  onSwitch={handleSwitchMember}
                  onProfileSaved={setProfile}
                />

                <div className="hidden md:block h-8 w-px bg-slate-200 dark:bg-slate-700 flex-shrink-0"/>

                <button
                  onClick={() => currentSessionId && handleEditSessionTitle(currentSessionId, currentSessionTitle)}
                  disabled={!currentSessionId}
                  className="hidden md:flex group/title flex-1 min-w-0 items-center gap-1.5 text-left disabled:cursor-default"
                  title={currentSessionId ? '重命名对话' : undefined}
                >
                  <span className="truncate text-sm text-slate-500 dark:text-slate-400 group-hover/title:text-primary-600 dark:group-hover/title:text-primary-400 transition-colors">
                    {currentSessionTitle || '新的学习对话'}
                  </span>
                  {currentSessionId && (
                    <Edit className="w-3.5 h-3.5 text-slate-400 opacity-0 group-hover/title:opacity-100 transition-opacity flex-shrink-0"/>
                  )}
                </button>

                {/* 手机端：页面操作入口并入本行（桌面端在页头，带文字） */}
                <div className="ml-auto flex md:hidden items-center gap-0.5 shrink-0">
                  {headerActions.map(({key, title, icon: Icon, onClick}) => (
                    <button
                      key={key}
                      onClick={onClick}
                      title={title}
                      aria-label={title}
                      className="p-1.5 border border-slate-200 dark:border-slate-600 rounded-lg text-slate-600 dark:text-slate-300 active:bg-primary-50 dark:active:bg-primary-900/30 transition-colors"
                    >
                      <Icon className="w-4 h-4"/>
                    </button>
                  ))}
                </div>
              </div>

              {/* 手机端：会话标题另起一行，半透明浅色展示（可点重命名） */}
              <button
                onClick={() => currentSessionId && handleEditSessionTitle(currentSessionId, currentSessionTitle)}
                disabled={!currentSessionId}
                className="md:hidden mt-1.5 flex w-full items-center gap-1 text-left disabled:cursor-default"
                title={currentSessionId ? '重命名对话' : undefined}
              >
                <span className="min-w-0 truncate text-xs text-slate-400/80 dark:text-slate-500/80">
                  {currentSessionTitle || '新的学习对话'}
                </span>
                {currentSessionId && (
                  <Edit className="w-3 h-3 flex-shrink-0 text-slate-400/70 dark:text-slate-500/70"/>
                )}
              </button>
            </div>

            {/* 消息列表 */}
            <div className="flex-1 min-h-0 relative dark:bg-slate-800">
              {messages.length === 0 ? (
                <div className="absolute inset-0 flex flex-col items-center justify-center px-4 md:px-6">
                  <MessageSquare className="w-10 h-10 md:w-12 md:h-12 mx-auto mb-2 md:mb-3 text-slate-400 dark:text-slate-500 opacity-50"/>
                  <p className="text-sm text-slate-500 dark:text-slate-400 mb-1">
                    {profile ? `${profile.nickname}，今天想学点什么？` : '今天想学点什么？'}
                  </p>
                  <p className="hidden md:block text-xs text-slate-400 dark:text-slate-500 mb-6">
                    AI 会自动检索知识库，学过的知识点会记入你的学习台账
                  </p>
                  <div className="flex flex-wrap justify-center gap-1.5 md:gap-2 max-w-xl mt-2 md:mt-0">
                    {SUGGESTIONS.map((suggestion) => (
                      <button
                        key={suggestion}
                        onClick={() => handleSubmitQuestion(suggestion)}
                        className="px-2.5 py-1 md:px-3 md:py-1.5 text-[11px] md:text-xs bg-slate-50 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded-full hover:bg-primary-50 dark:hover:bg-primary-900/30 hover:text-primary-600 dark:hover:text-primary-400 transition-colors"
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
                    <div className="pb-3 px-3 md:pb-4 md:px-4 first:pt-3 md:first:pt-4 dark:bg-slate-800">
                      <motion.div
                        initial={{opacity: 0, y: 10}}
                        animate={{opacity: 1, y: 0}}
                        className={`flex ${msg.type === 'user' ? 'justify-end' : 'justify-start'}`}
                      >
                        <div
                          className={`rounded-2xl p-3 md:p-4 shadow-sm ${msg.type === 'user'
                            ? 'max-w-[85%] bg-primary-600 text-white'
                            : 'w-full min-w-0 bg-white dark:bg-slate-800 border border-slate-100 dark:border-slate-600 text-slate-800 dark:text-slate-100'
                          }`}
                        >
                          {msg.type === 'user' ? (
                            <p className="whitespace-pre-wrap leading-relaxed text-sm">{msg.content}</p>
                          ) : (
                            <div>{renderAssistantBody(msg, index)}</div>
                          )}
                        </div>
                      </motion.div>
                    </div>
                  )}
                />
              )}
            </div>

            {/* 输入区域：有待回答提问时，选项面板从输入框上方弹出（回答后移入正文） */}
            <div className="relative p-4 border-t border-slate-200 dark:border-slate-600">
              <AnimatePresence>
                {askPopupOpen && pendingAsk && (
                  <motion.div
                    initial={{opacity: 0, y: 16, scale: 0.98}}
                    animate={{opacity: 1, y: 0, scale: 1}}
                    exit={{opacity: 0, y: 12, scale: 0.98}}
                    transition={{duration: 0.18, ease: 'easeOut'}}
                    className="absolute bottom-full left-4 right-4 z-30 mb-3 rounded-2xl border border-primary-100 dark:border-primary-900/60 bg-white dark:bg-slate-800 shadow-xl shadow-slate-900/10 p-4"
                  >
                    <div className="flex items-start gap-2 mb-3">
                      <CircleHelp className="w-4 h-4 text-primary-500 mt-0.5 flex-shrink-0 animate-pulse"/>
                      <p className="text-sm font-medium text-slate-700 dark:text-slate-200 leading-relaxed">
                        {pendingAsk.question}
                      </p>
                    </div>
                    {pendingAsk.options.length > 0 ? (
                      <>
                        <div className="flex flex-col gap-1.5">
                          {pendingAsk.options.map((option, i) => (
                            <button
                              key={option}
                              onClick={() => handleAnswerAsk(pendingAsk.askId, option)}
                              className="group flex w-full items-center gap-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-700/60 px-3.5 py-2.5 text-left text-sm text-slate-600 dark:text-slate-200 transition-all hover:border-primary-400 hover:bg-primary-50/60 dark:hover:border-primary-500/60 dark:hover:bg-primary-900/30 hover:text-primary-600 dark:hover:text-primary-300"
                            >
                              <span className="flex h-5 w-5 flex-shrink-0 items-center justify-center rounded-md bg-slate-100 dark:bg-slate-600 text-xs font-medium text-slate-400 dark:text-slate-300 group-hover:bg-primary-500 group-hover:text-white transition-colors">
                                {i + 1}
                              </span>
                              <span className="leading-relaxed">{option}</span>
                            </button>
                          ))}
                        </div>
                        <p className="mt-2.5 text-xs text-slate-400 dark:text-slate-500">
                          点击选项或按数字键回答，也可以在下方输入其他回答
                        </p>
                      </>
                    ) : (
                      <p className="text-xs text-slate-400 dark:text-slate-500">在下方输入你的回答，回车提交</p>
                    )}
                  </motion.div>
                )}
              </AnimatePresence>
              <div className="flex gap-3">
                <input
                  type="text"
                  value={question}
                  onChange={(e) => setQuestion(e.target.value)}
                  onKeyPress={(e) => e.key === 'Enter' && !e.shiftKey && handleSubmitQuestion()}
                  placeholder={askPopupOpen ? '输入其他回答，回车提交…' : '问点什么，比如：帮我入门 Redis…'}
                  className="flex-1 px-4 py-2.5 border border-slate-200 dark:border-slate-600 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent text-sm bg-white dark:bg-slate-700 text-slate-900 dark:text-white placeholder-slate-400"
                  disabled={loading && !askPopupOpen}
                />
                <motion.button
                  onClick={() => handleSubmitQuestion()}
                  disabled={!question.trim() || (loading && !askPopupOpen)}
                  className="px-5 py-2.5 bg-primary-500 text-white rounded-xl font-medium hover:bg-primary-600 transition-all disabled:opacity-50 disabled:cursor-not-allowed text-sm"
                  whileHover={{scale: loading && !askPopupOpen ? 1 : 1.02}}
                  whileTap={{scale: loading && !askPopupOpen ? 1 : 0.98}}
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
    </div>
  );
}

import {useState} from 'react';
import {
  AlertTriangle,
  BookOpenCheck,
  Brain,
  Check,
  ChevronDown,
  ChevronRight,
  CircleHelp,
  Library,
  ListTodo,
  Loader2,
  Search,
  UserPen,
  UserRound,
  Wrench,
} from 'lucide-react';
import type {AgentStep, ToolInvocation} from '../../types/learning';

const TOOL_LABELS: Record<string, string> = {
  searchKnowledgeBase: '检索知识库',
  upsertLearningRecord: '记录知识点',
  listLearnedTopics: '查看学习台账',
  getLearnerProfile: '读取学员档案',
  updateLearnerProfile: '更新学员档案',
  loadSkillBaseline: '加载知识基线',
  upsertLearningPlan: '固化学习计划',
  askLearner: '向学员提问',
};

const TOOL_ICONS: Record<string, React.ComponentType<{ className?: string }>> = {
  searchKnowledgeBase: Search,
  upsertLearningRecord: BookOpenCheck,
  listLearnedTopics: Library,
  getLearnerProfile: UserRound,
  updateLearnerProfile: UserPen,
  loadSkillBaseline: BookOpenCheck,
  upsertLearningPlan: ListTodo,
  askLearner: CircleHelp,
};

/** 将 start/end/error 事件流按次配对（历史消息的 toolSteps JSON 同构） */
export function groupInvocations(steps: AgentStep[]): ToolInvocation[] {
  const invocations: ToolInvocation[] = [];
  let current: ToolInvocation | null = null;
  for (const step of steps) {
    if (step.phase === 'start') {
      current = {tool: step.tool, argsSummary: step.summary, status: 'running', resultSummary: ''};
      invocations.push(current);
    } else if (current && step.tool === current.tool) {
      current.status = step.phase === 'error' ? 'error' : 'ok';
      current.resultSummary = step.summary;
      current.detail = step.detail;
      current = null;
    }
  }
  return invocations;
}

/**
 * 思考片段：流式中默认展开并显示「思考中…」，该片段结束后自动收起
 */
export function ReasoningBlock({text, streaming}: { text: string; streaming: boolean }) {
  return (
    <details className="mb-3" open={streaming}>
      <summary className="cursor-pointer select-none list-none inline-flex items-center gap-1.5 text-xs text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300">
        <Brain className={`w-3.5 h-3.5 ${streaming ? 'animate-pulse text-primary-500' : ''}`}/>
        {streaming ? '思考中…' : '已深度思考'}
      </summary>
      <div className="mt-2 px-3 py-2 rounded-xl bg-slate-50 dark:bg-slate-700/60 text-xs leading-relaxed text-slate-500 dark:text-slate-400 whitespace-pre-wrap max-h-60 overflow-y-auto">
        {text}
      </div>
    </details>
  );
}

/**
 * 工具调用卡片：单行「状态 + 工具 + 入参」，宽度随内容收缩（不铺满整行），点开可看工具返回原文；
 * 与思考/正文按发生顺序交错排列
 */
export function ToolCallBlock({invocation}: { invocation: ToolInvocation }) {
  const [expanded, setExpanded] = useState(false);
  const Icon = TOOL_ICONS[invocation.tool] ?? Wrench;
  const label = TOOL_LABELS[invocation.tool] ?? invocation.tool;
  // 摘要形如「加载知识基线：AGENT_BASIS」：前缀与标签重复时只留后半段（无入参的工具只回标签，结果为空白）
  const rawArgs = (invocation.argsSummary ?? '').trim();
  const args = rawArgs.startsWith(label)
    ? rawArgs.slice(label.length).replace(/^[:：]\s*/, '')
    : rawArgs;
  const expandable = !!invocation.detail;

  return (
    <div className="mb-3 w-fit max-w-full rounded-xl border border-slate-100 dark:border-slate-700 bg-slate-50 dark:bg-slate-700/50">
      <button
        type="button"
        onClick={() => expandable && setExpanded((value) => !value)}
        disabled={!expandable}
        title={expandable ? '点击查看工具返回内容' : invocation.argsSummary}
        className={`flex max-w-full items-center gap-2 px-3 py-2 text-left text-xs transition-colors ${
          expandable ? 'cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700' : 'cursor-default'
        }`}
      >
        {invocation.status === 'running' ? (
          <Loader2 className="w-3.5 h-3.5 animate-spin text-primary-500 flex-shrink-0"/>
        ) : invocation.status === 'error' ? (
          <AlertTriangle className="w-3.5 h-3.5 text-red-500 flex-shrink-0"/>
        ) : (
          <Check className="w-3.5 h-3.5 text-green-500 flex-shrink-0"/>
        )}
        <Icon className="w-3.5 h-3.5 text-slate-400 dark:text-slate-500 flex-shrink-0"/>
        <span className="font-medium text-slate-600 dark:text-slate-300 flex-shrink-0">{label}</span>
        {args && (
          <span className="min-w-0 truncate text-slate-400 dark:text-slate-500">{args}</span>
        )}
        {expandable && (
          <span className="flex items-center gap-0.5 text-[10px] font-mono text-primary-500 flex-shrink-0">
            结果
            {expanded ? <ChevronDown className="w-3 h-3"/> : <ChevronRight className="w-3 h-3"/>}
          </span>
        )}
      </button>
      {expanded && invocation.detail && (
        <pre className="mx-3 mb-2.5 px-2.5 py-2 rounded-lg border border-slate-100 dark:border-slate-700 bg-white dark:bg-slate-800/60 text-[11px] leading-relaxed text-slate-500 dark:text-slate-400 whitespace-pre-wrap max-h-48 overflow-y-auto">
          {invocation.detail}
        </pre>
      )}
    </div>
  );
}

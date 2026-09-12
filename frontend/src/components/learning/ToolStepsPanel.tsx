import {useMemo, useState} from 'react';
import {
  AlertTriangle,
  BookOpenCheck,
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
import type {AgentStep} from '../../types/learning';

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

/** 一次工具调用 = start + end/error 事件配对后的完整执行记录 */
interface ToolInvocation {
  tool: string;
  argsSummary: string;
  status: 'running' | 'ok' | 'error';
  resultSummary: string;
  detail?: string;
}

/** 将 start/end/error 事件流按次配对（历史消息的 toolSteps JSON 同构） */
function groupInvocations(steps: AgentStep[]): ToolInvocation[] {
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

interface ToolStepsPanelProps {
  steps: AgentStep[];
  /** 该条消息正在流式回答中 */
  running: boolean;
}

/**
 * Agent 工具调用收缩框：执行中自动展开直播进度，全部执行完自动收起；
 * 点头部可手动开合，带结果摘要的步骤可再点开查看工具返回内容
 */
export default function ToolStepsPanel({steps, running}: ToolStepsPanelProps) {
  const invocations = useMemo(() => groupInvocations(steps), [steps]);
  // null = 跟随自动开合；手动开合后覆盖默认行为
  const [pinned, setPinned] = useState<boolean | null>(null);
  const [openDetail, setOpenDetail] = useState<number | null>(null);

  if (invocations.length === 0) {
    return null;
  }

  const runningInvocation = invocations.find((inv) => inv.status === 'running');
  const autoOpen = running && !!runningInvocation;
  const open = pinned ?? autoOpen;

  const digest = Object.entries(
    invocations.reduce<Record<string, number>>((acc, inv) => {
      const label = TOOL_LABELS[inv.tool] ?? inv.tool;
      acc[label] = (acc[label] ?? 0) + 1;
      return acc;
    }, {})
  )
    .map(([label, count]) => (count > 1 ? `${label} ×${count}` : label))
    .slice(0, 3)
    .join(' · ');

  return (
    <div className="mb-3">
      <button
        onClick={() => setPinned(!open)}
        className="w-full flex items-center gap-2 px-3 py-2 rounded-lg bg-slate-50 dark:bg-slate-700/60 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors text-xs"
      >
        {runningInvocation ? (
          <Loader2 className="w-3.5 h-3.5 animate-spin text-primary-500 flex-shrink-0"/>
        ) : (
          <Wrench className="w-3.5 h-3.5 text-slate-400 dark:text-slate-500 flex-shrink-0"/>
        )}
        <span className="font-medium text-slate-600 dark:text-slate-300 flex-shrink-0">
          {runningInvocation ? '正在调用工具' : `已执行 ${invocations.length} 次工具调用`}
        </span>
        <span className="truncate text-slate-400 dark:text-slate-500 flex-1 text-left">
          {runningInvocation ? runningInvocation.argsSummary : digest}
        </span>
        <ChevronDown
          className={`w-3.5 h-3.5 text-slate-400 flex-shrink-0 transition-transform ${open ? 'rotate-180' : ''}`}
        />
      </button>

      {open && (
        <div className="mt-1.5 rounded-lg border border-slate-100 dark:border-slate-700 divide-y divide-slate-100 dark:divide-slate-700 overflow-hidden">
          {invocations.map((inv, index) => {
            const Icon = TOOL_ICONS[inv.tool] ?? Wrench;
            const expandable = !!inv.detail;
            const expanded = openDetail === index;
            return (
              <div key={index}>
                <button
                  onClick={() => expandable && setOpenDetail(expanded ? null : index)}
                  disabled={!expandable}
                  className={`w-full flex items-center gap-2 px-3 py-1.5 text-left text-xs bg-white dark:bg-slate-800 transition-colors ${
                    expandable ? 'cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-700/60' : 'cursor-default'
                  }`}
                  title={expandable ? '点击查看工具结果' : inv.argsSummary}
                >
                  {inv.status === 'running' ? (
                    <Loader2 className="w-3.5 h-3.5 animate-spin text-primary-500 flex-shrink-0"/>
                  ) : inv.status === 'error' ? (
                    <AlertTriangle className="w-3.5 h-3.5 text-red-500 flex-shrink-0"/>
                  ) : (
                    <Check className="w-3.5 h-3.5 text-green-500 flex-shrink-0"/>
                  )}
                  <Icon className="w-3.5 h-3.5 text-slate-400 dark:text-slate-500 flex-shrink-0"/>
                  <span className="font-medium text-slate-600 dark:text-slate-300 flex-shrink-0">
                    {TOOL_LABELS[inv.tool] ?? inv.tool}
                  </span>
                  <span className="truncate text-slate-400 dark:text-slate-500 flex-1">
                    {inv.status === 'running' ? inv.argsSummary : inv.resultSummary || inv.argsSummary}
                  </span>
                  {expandable && (
                    <span className="flex items-center gap-0.5 text-[10px] font-mono text-primary-500 flex-shrink-0">
                      结果
                      {expanded ? (
                        <ChevronDown className="w-3 h-3"/>
                      ) : (
                        <ChevronRight className="w-3 h-3"/>
                      )}
                    </span>
                  )}
                </button>
                {expanded && inv.detail && (
                  <pre className="px-3 py-2 bg-slate-50 dark:bg-slate-900/60 text-[11px] leading-relaxed text-slate-500 dark:text-slate-400 whitespace-pre-wrap max-h-48 overflow-y-auto">
                    {inv.detail}
                  </pre>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

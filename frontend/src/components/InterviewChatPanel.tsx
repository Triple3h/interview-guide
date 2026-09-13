import {useMemo, useRef} from 'react';
import {motion} from 'framer-motion';
import {Virtuoso, type VirtuosoHandle} from 'react-virtuoso';
import type {InterviewQuestion, InterviewSession} from '../types/interview';
import {Send} from 'lucide-react';
import InterviewMessageBubble from './InterviewMessageBubble';

interface Message {
  type: 'interviewer' | 'user';
  content: string;
  category?: string;
  questionIndex?: number;
}

interface InterviewChatPanelProps {
  session: InterviewSession;
  currentQuestion: InterviewQuestion | null;
  messages: Message[];
  answer: string;
  onAnswerChange: (answer: string) => void;
  onSubmit: () => void;
  onCompleteEarly: () => void;
  isSubmitting: boolean;
  showCompleteConfirm: boolean;
  onShowCompleteConfirm: (show: boolean) => void;
}

/**
 * 面试聊天面板组件
 */
export default function InterviewChatPanel({
  session,
  currentQuestion,
  messages,
  answer,
  onAnswerChange,
  onSubmit,
  // onCompleteEarly, // 暂时未使用
  isSubmitting,
  // showCompleteConfirm, // 暂时未使用
  onShowCompleteConfirm
}: InterviewChatPanelProps) {
  const virtuosoRef = useRef<VirtuosoHandle>(null);

  const progress = useMemo(() => {
    if (!session || !currentQuestion) return 0;
    return ((currentQuestion.questionIndex + 1) / session.totalQuestions) * 100;
  }, [session, currentQuestion]);

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      onSubmit();
    }
  };

  return (
    <div className="flex flex-col h-[calc(100vh-200px)] max-w-4xl mx-auto max-md:h-[calc(100dvh-5.5rem)]">
      {/* 进度条 */}
        <div
            className="bg-white dark:bg-slate-800 rounded-2xl p-6 mb-4 shadow-sm dark:shadow-slate-900/50 border border-slate-100 dark:border-slate-700 max-md:mb-2.5 max-md:rounded-2xl max-md:border-0 max-md:bg-slate-50 max-md:px-3 max-md:py-2.5 max-md:shadow-none max-md:dark:bg-slate-800">
        <div className="flex items-center justify-between mb-3">
          <span className="text-sm font-semibold text-slate-700 dark:text-slate-300">
            题目 {currentQuestion ? currentQuestion.questionIndex + 1 : 0} / {session.totalQuestions}
          </span>
            <span className="text-sm text-slate-500 dark:text-slate-400">
            {Math.round(progress)}%
          </span>
        </div>
            <div className="h-2 bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden">
          <motion.div
            className="h-full bg-gradient-to-r from-primary-500 to-primary-600 rounded-full max-md:bg-none max-md:bg-primary-500"
            initial={{ width: 0 }}
            animate={{ width: `${progress}%` }}
            transition={{ duration: 0.3 }}
          />
        </div>
      </div>

      {/* 聊天区域 */}
        <div
            className="flex-1 bg-white dark:bg-slate-800 rounded-2xl shadow-sm dark:shadow-slate-900/50 overflow-hidden flex flex-col min-h-0 border border-slate-100 dark:border-slate-700 max-md:rounded-2xl max-md:border-0 max-md:bg-transparent max-md:shadow-none max-md:dark:bg-transparent">
        <Virtuoso
          ref={virtuosoRef}
          data={messages}
          initialTopMostItemIndex={messages.length - 1}
          followOutput="smooth"
          className="flex-1"
          itemContent={(_index, msg) => (
            <div className="pb-4 px-6 first:pt-6 max-md:px-4 max-md:pb-3">
              <InterviewMessageBubble
                role={msg.type === 'interviewer' ? 'interviewer' : 'user'}
                text={msg.content}
                category={msg.category}
              />
            </div>
          )}
        />

        {/* 输入区域：提交是唯一主操作（右侧大按钮，拇指区），提前交卷是危险的次要操作，降级到下一行右侧 */}
            <div className="border-t border-slate-200 dark:border-slate-600 p-3 md:p-4 bg-slate-50 dark:bg-slate-700/50 max-md:border-t-0 max-md:bg-transparent max-md:p-0 max-md:pt-2 max-md:dark:bg-transparent">
          <div className="flex items-end gap-2 md:gap-3 max-md:gap-0 max-md:rounded-[24px] max-md:border max-md:border-slate-200 max-md:dark:border-slate-600 max-md:bg-white max-md:dark:bg-slate-800 max-md:py-1 max-md:pl-4 max-md:pr-1.5 max-md:shadow-sm">
            <textarea
              value={answer}
              onChange={(e) => onAnswerChange(e.target.value)}
              onKeyDown={handleKeyPress}
              placeholder="输入你的回答..."
              className="flex-1 px-3 py-3 md:px-4 border border-slate-300 dark:border-slate-500 rounded-xl focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-transparent resize-none bg-white dark:bg-slate-800 text-slate-900 dark:text-white placeholder-slate-400 dark:placeholder-slate-500 max-md:border-0 max-md:bg-transparent max-md:px-0 max-md:py-2 max-md:text-[15px] max-md:placeholder:text-slate-400 max-md:focus:ring-0 max-md:dark:bg-transparent"
              rows={3}
              disabled={isSubmitting}
            />
            <motion.button
              onClick={onSubmit}
              disabled={!answer.trim() || isSubmitting}
              className="shrink-0 h-12 px-4 md:px-6 bg-primary-500 text-white rounded-xl font-medium text-sm hover:bg-primary-600 transition-colors disabled:opacity-50 disabled:cursor-not-allowed inline-flex items-center justify-center gap-2 max-md:h-9 max-md:w-9 max-md:rounded-full max-md:p-0"
              whileHover={{ scale: isSubmitting || !answer.trim() ? 1 : 1.02 }}
              whileTap={{ scale: isSubmitting || !answer.trim() ? 1 : 0.98 }}
            >
              {isSubmitting ? (
                <>
                  <motion.div
                    className="w-4 h-4 border-2 border-white border-t-transparent rounded-full"
                    animate={{ rotate: 360 }}
                    transition={{ duration: 1, repeat: Infinity, ease: "linear" }}
                  />
                  <span className="max-md:hidden">提交中</span>
                </>
              ) : (
                <>
                  <Send className="w-4 h-4" />
                  <span className="max-md:hidden">提交</span>
                </>
              )}
            </motion.button>
          </div>
          <div className="mt-2 flex items-center justify-between gap-2">
            <p className="hidden md:block text-xs text-slate-400 dark:text-slate-500">Ctrl / Cmd + Enter 快速提交</p>
            <motion.button
              onClick={() => onShowCompleteConfirm(true)}
              disabled={isSubmitting}
              className="ml-auto shrink-0 inline-flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-200/70 dark:hover:bg-slate-600/60 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              whileTap={{ scale: isSubmitting ? 1 : 0.96 }}
            >
              提前交卷
            </motion.button>
          </div>
        </div>
      </div>
    </div>
  );
}

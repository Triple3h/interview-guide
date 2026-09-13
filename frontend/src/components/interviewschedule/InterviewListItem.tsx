// frontend/src/components/interviewschedule/InterviewListItem.tsx

import React from 'react';
import { motion } from 'framer-motion';
import { Edit2, Trash2, ExternalLink } from 'lucide-react';
import dayjs from 'dayjs';
import type { InterviewSchedule, InterviewStatus } from '../../types/interviewSchedule';

interface InterviewListItemProps {
  interview: InterviewSchedule;
  onEdit: () => void;
  onDelete: () => void;
  onStatusChange: (status: InterviewStatus) => void;
}

const statusConfig: Record<InterviewStatus, { label: string; className: string }> = {
  PENDING: {
    label: '待面试',
    className: 'bg-blue-500/10 dark:bg-blue-500/20 text-blue-700 dark:text-blue-300 border border-blue-300/30 dark:border-blue-400/30',
  },
  COMPLETED: {
    label: '已完成',
    className: 'bg-emerald-500/10 dark:bg-emerald-500/20 text-emerald-700 dark:text-emerald-300 border border-emerald-300/30 dark:border-emerald-400/30',
  },
  CANCELLED: {
    label: '已取消',
    className: 'bg-slate-500/10 dark:bg-slate-500/20 text-slate-700 dark:text-slate-300 border border-slate-300/30 dark:border-slate-400/30',
  },
  RESCHEDULED: {
    label: '已改期',
    className: 'bg-amber-500/10 dark:bg-amber-500/20 text-amber-700 dark:text-amber-300 border border-amber-300/30 dark:border-amber-400/30',
  },
};

const typeLabels: Record<string, string> = {
  ONSITE: '现场面试',
  VIDEO: '视频面试',
  PHONE: '电话面试',
};

export const InterviewListItem: React.FC<InterviewListItemProps> = ({
  interview,
  onEdit,
  onDelete,
  onStatusChange,
}) => {
  return (
    <motion.div
      initial={{ opacity: 0, y: 20 }}
      animate={{ opacity: 1, y: 0 }}
      whileHover={{ y: -2 }}
      transition={{ duration: 0.2 }}
      className="bg-white/90 dark:bg-slate-900/90 backdrop-blur-xl border border-slate-200/50 dark:border-slate-700/50 rounded-2xl p-3 md:p-6 hover:shadow-2xl hover:shadow-slate-200/50 dark:hover:shadow-slate-900/50 hover:-translate-y-0.5 transition-all"
    >
      <div className="flex items-start justify-between gap-3 md:gap-4">
        <div className="flex-1 min-w-0">
          <div className="flex flex-wrap items-center gap-2 md:gap-3 mb-2 md:mb-3">
            <span className={`status-badge backdrop-blur-sm ${statusConfig[interview.status].className}`}>
              {statusConfig[interview.status].label}
            </span>
            <span className="text-xs md:text-sm font-medium text-slate-600 dark:text-slate-400">
              {dayjs(interview.interviewTime).format('YYYY-MM-DD HH:mm')}
            </span>
          </div>

          <h3 className="font-display font-bold text-base md:text-xl mb-1.5 md:mb-2 text-slate-900 dark:text-white tracking-tight">
            {interview.companyName}
          </h3>
          <p className="text-sm md:text-base text-slate-600 dark:text-slate-300 mb-2 md:mb-3 font-medium">{interview.position}</p>

          <div className="flex flex-wrap items-center gap-2 md:gap-3 text-xs md:text-sm text-slate-500 dark:text-slate-400">
            <span className="px-2 py-0.5 md:px-3 md:py-1 bg-slate-100 dark:bg-slate-800 rounded-lg font-medium">
              第 {interview.roundNumber} 轮
            </span>
            <span className="text-slate-300 dark:text-slate-600">•</span>
            <span className="font-medium">{typeLabels[interview.interviewType] || interview.interviewType}</span>
            {interview.interviewer && (
              <>
                <span className="text-slate-300 dark:text-slate-600">•</span>
                <span className="font-medium">{interview.interviewer}</span>
              </>
            )}
          </div>

          {interview.notes && (
            <p className="text-sm text-slate-500 dark:text-slate-400 mt-3 italic">{interview.notes}</p>
          )}
        </div>

        {/* 卡片级操作：编辑 / 删除。触控区 40px，删除与编辑留出间距降低误触 */}
        <div className="flex gap-2 md:gap-1.5">
          <motion.button
            whileHover={{ scale: 1.1 }}
            whileTap={{ scale: 0.9 }}
            onClick={onEdit}
            className="p-2.5 text-slate-400 dark:text-slate-500 hover:text-primary-600 dark:hover:text-primary-400 hover:bg-primary-500/10 dark:hover:bg-primary-500/20 active:bg-primary-500/10 dark:active:bg-primary-500/20 rounded-xl transition-colors"
            title="编辑"
            aria-label="编辑面试安排"
          >
            <Edit2 className="w-5 h-5" />
          </motion.button>
          <motion.button
            whileHover={{ scale: 1.1 }}
            whileTap={{ scale: 0.9 }}
            onClick={onDelete}
            className="p-2.5 text-slate-400 dark:text-slate-500 hover:text-red-600 dark:hover:text-red-400 hover:bg-red-500/10 dark:hover:bg-red-500/20 active:bg-red-500/10 dark:active:bg-red-500/20 rounded-xl transition-colors"
            title="删除"
            aria-label="删除面试安排"
          >
            <Trash2 className="w-5 h-5" />
          </motion.button>
        </div>
      </div>

      {interview.status === 'PENDING' && (
        <motion.div
          initial={{ opacity: 0, height: 0 }}
          animate={{ opacity: 1, height: 'auto' }}
          className="mt-3 pt-3 border-t border-slate-200 dark:border-slate-700 flex items-center gap-2 md:gap-3"
        >
          {/* 主操作：已填会议链接时「进入会议」优先（时间敏感），否则「标记为已完成」 */}
          {interview.meetingLink && (
            <motion.a
              href={interview.meetingLink}
              target="_blank"
              rel="noopener noreferrer"
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
              className="flex-1 md:flex-none inline-flex items-center justify-center gap-1.5 whitespace-nowrap px-3 md:px-4 py-2 text-xs md:text-sm font-medium rounded-xl
                bg-primary-50 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300 border border-primary-200 dark:border-primary-800 hover:bg-primary-100 dark:hover:bg-primary-900/50 transition-colors"
            >
              <ExternalLink className="w-3.5 h-3.5 md:w-4 md:h-4" />
              进入会议
            </motion.a>
          )}
          <motion.button
            whileHover={{ scale: 1.02 }}
            whileTap={{ scale: 0.98 }}
            onClick={() => onStatusChange('COMPLETED')}
            className="flex-1 md:flex-none whitespace-nowrap px-3 md:px-4 py-2 text-xs md:text-sm font-medium rounded-xl bg-emerald-500/10 dark:bg-emerald-500/20 text-emerald-700 dark:text-emerald-300 hover:bg-emerald-500/20 dark:hover:bg-emerald-500/30 border border-emerald-300/30 dark:border-emerald-400/30 transition-colors"
          >
            标记为已完成
          </motion.button>
          {/* 负向操作：无底色弱化 + 靠右隔离，避免与正向操作同权重被误触 */}
          <motion.button
            whileTap={{ scale: 0.98 }}
            onClick={() => onStatusChange('CANCELLED')}
            className="ml-auto md:ml-0 shrink-0 whitespace-nowrap px-2 py-2 text-xs md:text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-lg transition-colors"
          >
            取消面试
          </motion.button>
        </motion.div>
      )}
    </motion.div>
  );
};

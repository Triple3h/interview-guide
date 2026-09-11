import type { ReactNode, SelectHTMLAttributes } from 'react';
import { ChevronDown } from 'lucide-react';

/**
 * 全站统一下拉框样式
 * - form：表单字段，占满整行（w-full）
 * - filter：工具栏筛选，宽度自适应
 * - compact：卡片/行内紧凑控件（text-xs）
 * - mini：分页等小控件（text-sm）
 *
 * 原生箭头统一替换为右侧 ChevronDown 图标；特殊外观（宽度、边框色）通过 className 覆盖。
 * 注意：知识库的级联分类下拉（CategoryFilterSelect）是自绘树形下拉，不使用本组件。
 */
export type SelectVariant = 'form' | 'filter' | 'compact' | 'mini';

const VARIANT_STYLES: Record<SelectVariant, { wrapper: string; select: string; icon: string }> = {
  form: {
    wrapper: 'relative w-full',
    select: 'w-full pl-3.5 pr-9 py-2 rounded-lg text-sm',
    icon: 'right-3 w-4 h-4',
  },
  filter: {
    wrapper: 'relative',
    select: 'pl-3.5 pr-9 py-2 rounded-lg text-sm',
    icon: 'right-3 w-4 h-4',
  },
  compact: {
    wrapper: 'relative',
    select: 'pl-2.5 pr-7 py-1 rounded-md text-xs',
    icon: 'right-2 w-3.5 h-3.5',
  },
  mini: {
    wrapper: 'relative',
    select: 'pl-2.5 pr-7 py-1 rounded-md text-sm',
    icon: 'right-2 w-3.5 h-3.5',
  },
};

const BASE_SELECT_CLASS = [
  'appearance-none cursor-pointer border bg-white text-slate-900 transition-colors',
  'border-slate-200 hover:border-slate-300',
  'dark:border-slate-600 dark:bg-slate-700 dark:text-white dark:hover:border-slate-500',
  'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-400',
  'disabled:cursor-not-allowed disabled:opacity-50',
].join(' ');

export interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'className'> {
  variant?: SelectVariant;
  /** 透传到 select 元素，用于覆盖宽度、边框色等特殊样式 */
  className?: string;
  children: ReactNode;
}

export default function Select({ variant = 'form', className, children, ...rest }: SelectProps) {
  const styles = VARIANT_STYLES[variant];
  return (
    <div className={styles.wrapper}>
      <select {...rest} className={`${BASE_SELECT_CLASS} ${styles.select} ${className ?? ''}`}>
        {children}
      </select>
      <ChevronDown
        className={`pointer-events-none absolute top-1/2 -translate-y-1/2 text-slate-400 ${styles.icon}`}
      />
    </div>
  );
}

// 下拉面板共享样式：ui/Select（原生 select 替代品）与知识库级联分类下拉 CategoryFilterSelect 共用，
// 保证所有下拉的展开面板视觉一致

export const DROPDOWN_PANEL_CLASS = [
  'z-[60] overflow-hidden rounded-xl border border-slate-100 bg-white shadow-lg',
  'dark:border-slate-700 dark:bg-slate-800',
].join(' ');

export const DROPDOWN_LIST_CLASS = 'max-h-72 overflow-y-auto py-1.5';

const OPTION_BASE = 'flex w-full items-center gap-2 py-2 pr-3 text-left text-sm';

// 选项行：active 为键盘高亮项，selected 为当前选中项，disabled 为不可选项
export function dropdownOptionClass({
  active = false,
  selected = false,
  disabled = false,
}: {
  active?: boolean;
  selected?: boolean;
  disabled?: boolean;
} = {}): string {
  if (disabled) {
    return `${OPTION_BASE} pl-3 cursor-not-allowed text-slate-300 dark:text-slate-600`;
  }
  const tone = selected
    ? 'cursor-pointer bg-primary-50 font-medium text-primary-600 dark:bg-primary-900/20 dark:text-primary-400'
    : active
      ? 'cursor-pointer bg-slate-50 text-slate-900 dark:bg-slate-700/60 dark:text-white'
      : 'cursor-pointer text-slate-700 hover:bg-slate-50 dark:text-slate-200 dark:hover:bg-slate-700/60';
  return `${OPTION_BASE} pl-3 transition-colors ${tone}`;
}

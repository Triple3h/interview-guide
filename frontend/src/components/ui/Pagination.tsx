import type { ReactNode } from 'react';

const DEFAULT_PAGE_SIZE_OPTIONS = [10, 20, 50];

interface PaginationProps {
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
  onPageSizeChange: (size: number) => void;
  /** 计数单位，默认「条」 */
  unit?: string;
  /** 每页条数候选项，默认 10 / 20 / 50 */
  pageSizeOptions?: number[];
}

/**
 * 列表分页条
 *
 * <p>页码在组件内按 total 二次钳制：调用方传入越界页码时，展示与翻页回调都以安全页为准。
 */
export default function Pagination({
  page,
  pageSize,
  total,
  onPageChange,
  onPageSizeChange,
  unit = '条',
  pageSizeOptions = DEFAULT_PAGE_SIZE_OPTIONS,
}: PaginationProps) {
  if (total === 0) return null;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(Math.max(page, 0), totalPages - 1);
  const from = safePage * pageSize + 1;
  const to = Math.min((safePage + 1) * pageSize, total);

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 mt-4 px-1 text-sm">
      <div className="flex items-center gap-2 text-slate-500 dark:text-slate-400">
        <span>
          第 <span className="font-semibold text-slate-700 dark:text-slate-200">{from}-{to}</span> /
          共 <span className="font-semibold text-slate-700 dark:text-slate-200">{total}</span> {unit}
        </span>
        <span className="text-slate-300 dark:text-slate-600">|</span>
        <span>每页</span>
        {/* 横排按钮而非下拉：分页条在页面底部，下拉面板向下展开会被视口裁掉；整组用外框包住表示「同一档位选择」 */}
        <div className="inline-flex items-center gap-0.5 rounded-lg border border-slate-200 dark:border-slate-600 p-0.5">
          {pageSizeOptions.map(size => (
            <button
              key={size}
              onClick={() => onPageSizeChange(size)}
              title={`每页 ${size} ${unit}`}
              className={`px-2 py-1 rounded-md text-xs transition-colors ${
                size === pageSize
                  ? 'bg-primary-500 text-white hover:bg-primary-600'
                  : 'text-slate-500 dark:text-slate-400 hover:bg-primary-50 hover:text-primary-600 dark:hover:bg-primary-900/30 dark:hover:text-primary-400'
              }`}
            >
              {size}
            </button>
          ))}
        </div>
      </div>
      <div className="flex items-center gap-1">
        <PaginationButton
          disabled={safePage === 0}
          onClick={() => onPageChange(0)}
          title="第一页"
        >
          «
        </PaginationButton>
        <PaginationButton
          disabled={safePage === 0}
          onClick={() => onPageChange(safePage - 1)}
          title="上一页"
        >
          ‹
        </PaginationButton>
        <span className="px-3 text-slate-700 dark:text-slate-200">
          {safePage + 1} / {totalPages}
        </span>
        <PaginationButton
          disabled={safePage >= totalPages - 1}
          onClick={() => onPageChange(safePage + 1)}
          title="下一页"
        >
          ›
        </PaginationButton>
        <PaginationButton
          disabled={safePage >= totalPages - 1}
          onClick={() => onPageChange(totalPages - 1)}
          title="最后一页"
        >
          »
        </PaginationButton>
      </div>
    </div>
  );
}

function PaginationButton({
  children,
  disabled,
  onClick,
  title,
}: {
  children: ReactNode;
  disabled: boolean;
  onClick: () => void;
  title: string;
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={title}
      className="w-9 h-9 inline-flex items-center justify-center rounded-lg border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 hover:bg-primary-50 hover:text-primary-600 dark:hover:bg-primary-900/30 dark:hover:text-primary-400 disabled:opacity-40 disabled:cursor-not-allowed"
    >
      {children}
    </button>
  );
}

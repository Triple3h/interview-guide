import { Fragment, useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Check, ChevronDown, FolderTree } from 'lucide-react';
import type { CategoryTreeNode } from '../../api/knowledgebase';
import { DROPDOWN_LIST_CLASS, DROPDOWN_PANEL_CLASS } from '../ui/dropdownStyles';

/** 「未分类」筛选项的值：用双下划线包裹与真实分类名区分 */
export const UNCATEGORIZED_FILTER_VALUE = '__uncategorized__';

/** 各级缩进：一级不缩进，二级/三级逐级右移，更深层沿用最后一档 */
const INDENT_CLASSES = ['pl-3 pr-3', 'pl-8 pr-3', 'pl-12 pr-3'];

interface CategoryFilterSelectProps {
  tree: CategoryTreeNode[];
  /** '' 全部分类；'ai' 一级；'ai/agent' 二级；'ai/agent/rag' 三级；UNCATEGORIZED_FILTER_VALUE 未分类 */
  value: string;
  onChange: (value: string) => void;
  /** 是否在树末尾追加「未分类」选项（默认不展示） */
  includeUncategorized?: boolean;
}

interface OptionRowProps {
  selected: boolean;
  label: string;
  onClick: () => void;
  /** 层级深度，0 为一级分类，影响缩进与前置圆点 */
  depth?: number;
  /** 一级分类右侧的辅助文案（如「全部」） */
  suffix?: string;
  /** 带子分类时展示的文件夹图标 */
  showFolderIcon?: boolean;
}

/** 分类节点存的是完整路径，显示时只取最后一段 */
function categoryLabel(path: string): string {
  const slash = path.lastIndexOf('/');
  return slash < 0 ? path : path.slice(slash + 1);
}

function OptionRow({ selected, label, onClick, depth = 0, suffix, showFolderIcon = false }: OptionRowProps) {
  const indentClass = INDENT_CLASSES[Math.min(depth, INDENT_CLASSES.length - 1)];
  return (
    <button
      type="button"
      role="option"
      aria-selected={selected}
      onClick={onClick}
      title={label}
      className={`flex w-full items-center gap-2 py-2 text-left text-sm transition-colors ${indentClass} ${
        selected
          ? 'bg-primary-50 font-medium text-primary-600 dark:bg-primary-900/20 dark:text-primary-400'
          : 'text-slate-700 hover:bg-slate-50 dark:text-slate-200 dark:hover:bg-slate-700/60'
      }`}
    >
      {depth > 0 && (
        <span
          className={`h-1.5 w-1.5 shrink-0 rounded-full ${
            selected ? 'bg-primary-400 dark:bg-primary-500' : 'bg-slate-300 dark:bg-slate-600'
          }`}
        />
      )}
      {showFolderIcon && <FolderTree className="h-3.5 w-3.5 shrink-0 text-slate-400 dark:text-slate-500" />}
      <span className="flex-1 truncate">{label}</span>
      {suffix && !selected && <span className="shrink-0 text-xs text-slate-400 dark:text-slate-500">{suffix}</span>}
      {selected && <Check className="h-3.5 w-3.5 shrink-0" />}
    </button>
  );
}

/** 递归渲染分类树，每层都可直接选中（选中即匹配该层及其下全部） */
function CategoryTreeOptions({
  nodes,
  value,
  depth,
  onSelect,
}: {
  nodes: CategoryTreeNode[];
  value: string;
  depth: number;
  onSelect: (value: string) => void;
}) {
  return (
    <>
      {nodes.map(node => (
        <li key={node.name}>
          <OptionRow
            depth={depth}
            selected={value === node.name}
            label={categoryLabel(node.name)}
            suffix={node.children.length > 0 ? '全部' : undefined}
            showFolderIcon={node.children.length > 0}
            onClick={() => onSelect(node.name)}
          />
          {node.children.length > 0 && (
            <ul>
              <CategoryTreeOptions nodes={node.children} value={value} depth={depth + 1} onSelect={onSelect} />
            </ul>
          )}
        </li>
      ))}
    </>
  );
}

/**
 * 知识库分类筛选下拉：一棵分类树收敛到一个下拉框，
 * 支持任意层级（当前分类最多三级），选中某层即匹配「该层及其下全部」
 */
export default function CategoryFilterSelect({
  tree,
  value,
  onChange,
  includeUncategorized = false,
}: CategoryFilterSelectProps) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handlePointerDown = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [open]);

  const select = (next: string) => {
    onChange(next);
    setOpen(false);
  };

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen(prev => !prev)}
        aria-haspopup="listbox"
        aria-expanded={open}
        className="flex min-w-[9.5rem] cursor-pointer items-center gap-2 rounded-lg border border-slate-200 bg-white py-2 pl-4 pr-3 text-sm text-slate-900 transition-colors hover:border-slate-300 focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-slate-600 dark:bg-slate-700 dark:text-white dark:hover:border-slate-500"
      >
        <span className="flex flex-1 items-center gap-1 truncate">
          {value === UNCATEGORIZED_FILTER_VALUE ? (
            <span className="truncate">未分类</span>
          ) : value ? (
            value.split('/').map((part, index) => (
              <Fragment key={`${part}-${index}`}>
                {index > 0 && <span className="text-slate-300 dark:text-slate-500">/</span>}
                <span className={`truncate ${index === 0 ? '' : 'text-primary-600 dark:text-primary-400'}`}>
                  {part}
                </span>
              </Fragment>
            ))
          ) : (
            <span className="text-slate-600 dark:text-slate-300">全部分类</span>
          )}
        </span>
        <ChevronDown
          className={`h-4 w-4 shrink-0 text-slate-400 transition-transform duration-150 ${open ? 'rotate-180' : ''}`}
        />
      </button>

      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ opacity: 0, y: -4, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -4, scale: 0.98 }}
            transition={{ duration: 0.12 }}
            className={`absolute left-0 top-full mt-2 w-72 origin-top ${DROPDOWN_PANEL_CLASS}`}
          >
            <ul role="listbox" className={DROPDOWN_LIST_CLASS}>
              <li>
                <OptionRow selected={value === ''} label="全部分类" onClick={() => select('')} />
              </li>
              <CategoryTreeOptions nodes={tree} value={value} depth={0} onSelect={select} />
              {includeUncategorized && (
                <li>
                  <OptionRow
                    selected={value === UNCATEGORIZED_FILTER_VALUE}
                    label="未分类"
                    onClick={() => select(UNCATEGORIZED_FILTER_VALUE)}
                  />
                </li>
              )}
              {tree.length === 0 && !includeUncategorized && (
                <li className="px-3 py-2 text-xs text-slate-400 dark:text-slate-500">暂无分类</li>
              )}
            </ul>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

import { useEffect, useRef, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Check, ChevronDown, FolderTree } from 'lucide-react';
import type { CategoryTreeNode } from '../../api/knowledgebase';
import { DROPDOWN_LIST_CLASS, DROPDOWN_PANEL_CLASS } from '../ui/dropdownStyles';

interface CategoryFilterSelectProps {
  tree: CategoryTreeNode[];
  /** '' 表示全部分类；'ai' 为一级分类；'ai/agent' 为二级分类 */
  value: string;
  onChange: (value: string) => void;
}

interface OptionRowProps {
  selected: boolean;
  label: string;
  onClick: () => void;
  /** 二级分类的缩进样式 */
  indent?: boolean;
  /** 一级分类右侧的辅助文案（如「全部」） */
  suffix?: string;
  /** 一级分类带二级时展示的图标 */
  showFolderIcon?: boolean;
}

function splitCategory(value: string): { parent: string; child: string | null } {
  const slash = value.indexOf('/');
  if (slash <= 0) return { parent: value, child: null };
  return { parent: value.slice(0, slash), child: value.slice(slash + 1) };
}

function OptionRow({ selected, label, onClick, indent = false, suffix, showFolderIcon = false }: OptionRowProps) {
  return (
    <button
      type="button"
      role="option"
      aria-selected={selected}
      onClick={onClick}
      className={`flex w-full items-center gap-2 py-2 text-left text-sm transition-colors ${
        indent ? 'pl-10 pr-3' : 'pl-3 pr-3'
      } ${
        selected
          ? 'bg-primary-50 font-medium text-primary-600 dark:bg-primary-900/20 dark:text-primary-400'
          : 'text-slate-700 hover:bg-slate-50 dark:text-slate-200 dark:hover:bg-slate-700/60'
      }`}
    >
      {indent && (
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

/**
 * 知识库分类筛选下拉：一棵分类树收敛到一个下拉框，
 * 一级分类直接可选，存在二级时在下方缩进展示，选中二级时值为「一级/二级」
 */
export default function CategoryFilterSelect({ tree, value, onChange }: CategoryFilterSelectProps) {
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

  const { parent, child } = splitCategory(value);

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
          {value ? (
            <>
              <span className="truncate">{parent}</span>
              {child && (
                <>
                  <span className="text-slate-300 dark:text-slate-500">/</span>
                  <span className="truncate text-primary-600 dark:text-primary-400">{child}</span>
                </>
              )}
            </>
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
            className={`absolute left-0 top-full mt-2 w-64 origin-top ${DROPDOWN_PANEL_CLASS}`}
          >
            <ul role="listbox" className={DROPDOWN_LIST_CLASS}>
              <li>
                <OptionRow selected={value === ''} label="全部分类" onClick={() => select('')} />
              </li>
              {tree.map(node => (
                <li key={node.name}>
                  <OptionRow
                    selected={value === node.name}
                    label={node.name}
                    suffix={node.children.length > 0 ? '全部' : undefined}
                    showFolderIcon={node.children.length > 0}
                    onClick={() => select(node.name)}
                  />
                  {node.children.length > 0 && (
                    <ul>
                      {node.children.map(childName => {
                        const childValue = `${node.name}/${childName}`;
                        return (
                          <li key={childValue}>
                            <OptionRow
                              indent
                              selected={value === childValue}
                              label={childName}
                              onClick={() => select(childValue)}
                            />
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </li>
              ))}
              {tree.length === 0 && (
                <li className="px-3 py-2 text-xs text-slate-400 dark:text-slate-500">暂无分类</li>
              )}
            </ul>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

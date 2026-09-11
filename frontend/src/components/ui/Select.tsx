import { Children, Fragment, isValidElement, useCallback, useEffect, useRef, useState } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { Check, ChevronDown } from 'lucide-react';
import { DROPDOWN_LIST_CLASS, DROPDOWN_PANEL_CLASS, dropdownOptionClass } from './dropdownStyles';

/**
 * 全站统一下拉框（自绘，替代原生 select，展开面板样式全站一致）
 * - form：表单字段，占满整行（w-full）
 * - filter：工具栏筛选，宽度自适应
 * - compact：卡片/行内紧凑控件（text-xs）
 * - mini：分页等小控件（text-sm）
 *
 * 用法与原生 select 一致（value + option children + onChange(e.target.value)），
 * 但 onChange 的 e 是 { target: { value: string } } 形态的轻量事件对象。
 * 面板通过 portal 渲染到 body，避免被 overflow-hidden 容器裁剪。
 * 注意：知识库的级联分类下拉（CategoryFilterSelect）是带层级的自绘下拉，不使用本组件。
 */
export type SelectVariant = 'form' | 'filter' | 'compact' | 'mini';

const VARIANT_STYLES: Record<SelectVariant, { wrapper: string; trigger: string; icon: string }> = {
  form: {
    wrapper: 'relative w-full',
    trigger: 'w-full pl-3.5 pr-3 py-2 rounded-lg text-sm',
    icon: 'w-4 h-4',
  },
  filter: {
    wrapper: 'relative',
    // w-full：让外层传入的宽度类（如 sm:w-40）真正作用到触发器，避免按钮按内容宽
    // 度收缩、在工具栏里留出一段看不见的空白
    trigger: 'w-full pl-3.5 pr-3 py-2 rounded-lg text-sm',
    icon: 'w-4 h-4',
  },
  compact: {
    wrapper: 'relative',
    trigger: 'pl-2.5 pr-2 py-1 rounded-md text-xs',
    icon: 'w-3.5 h-3.5',
  },
  mini: {
    wrapper: 'relative',
    trigger: 'pl-2.5 pr-2 py-1 rounded-md text-sm',
    icon: 'w-3.5 h-3.5',
  },
};

const BASE_TRIGGER_CLASS = [
  'flex cursor-pointer items-center gap-2 border bg-white text-left text-slate-900 transition-colors',
  'border-slate-200 hover:border-slate-300',
  'dark:border-slate-600 dark:bg-slate-700 dark:text-white dark:hover:border-slate-500',
  'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:border-primary-400',
  'disabled:cursor-not-allowed disabled:opacity-50',
].join(' ');

interface ParsedOption {
  value: string;
  label: string;
  disabled: boolean;
}

function extractText(node: ReactNode): string {
  if (node === null || node === undefined || typeof node === 'boolean') return '';
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(extractText).join('');
  if (isValidElement(node)) return extractText((node.props as { children?: ReactNode }).children);
  return '';
}

// 从 option children 中解析选项（支持数组、条件渲染与 Fragment）
function collectOptions(children: ReactNode, out: ParsedOption[] = []): ParsedOption[] {
  Children.forEach(children, child => {
    if (!isValidElement(child)) return;
    if (child.type === 'option') {
      const props = child.props as { value?: unknown; disabled?: boolean; children?: ReactNode };
      const label = extractText(props.children);
      const value = props.value === undefined || props.value === null ? label : String(props.value);
      out.push({ value, label, disabled: Boolean(props.disabled) });
      return;
    }
    if (child.type === Fragment) {
      collectOptions((child.props as { children?: ReactNode }).children, out);
    }
  });
  return out;
}

export interface SelectProps {
  variant?: SelectVariant;
  /** 受控值；与 option 的 value 按字符串比较，undefined/null 视为空值 */
  value?: string | number | null;
  /** 兼容原生 select 写法：通过 e.target.value 取字符串值 */
  onChange?: (event: { target: { value: string } }) => void;
  /** 作用于外层容器，用于覆盖宽度、间距等（如 sm:w-40） */
  className?: string;
  disabled?: boolean;
  title?: string;
  'aria-label'?: string;
  children: ReactNode;
}

export default function Select({
  variant = 'form',
  value,
  onChange,
  className = '',
  disabled = false,
  title,
  'aria-label': ariaLabel,
  children,
}: SelectProps) {
  const options = collectOptions(children);
  const selectedValue = value === undefined || value === null ? '' : String(value);
  const selectedIndex = options.findIndex(option => option.value === selectedValue);
  const styles = VARIANT_STYLES[variant];

  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const [panelRect, setPanelRect] = useState<{ top: number; left: number; width: number } | null>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  const close = useCallback(() => setOpen(false), []);

  const updatePanelRect = useCallback(() => {
    const el = triggerRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    setPanelRect({ top: rect.bottom + 6, left: rect.left, width: rect.width });
  }, []);

  useEffect(() => {
    if (!open) return;
    updatePanelRect();
    const handlePointerDown = (event: MouseEvent) => {
      const target = event.target as Node;
      if (triggerRef.current?.contains(target) || panelRef.current?.contains(target)) return;
      close();
    };
    const handleViewportChange = () => updatePanelRect();
    document.addEventListener('mousedown', handlePointerDown);
    window.addEventListener('resize', handleViewportChange);
    window.addEventListener('scroll', handleViewportChange, true);
    return () => {
      document.removeEventListener('mousedown', handlePointerDown);
      window.removeEventListener('resize', handleViewportChange);
      window.removeEventListener('scroll', handleViewportChange, true);
    };
  }, [open, close, updatePanelRect]);

  const openPanel = () => {
    if (disabled) return;
    const firstEnabled = options.findIndex(option => !option.disabled);
    setActiveIndex(selectedIndex >= 0 ? selectedIndex : firstEnabled >= 0 ? firstEnabled : 0);
    setOpen(true);
  };

  const selectOption = (option: ParsedOption) => {
    if (option.disabled) return;
    close();
    if (option.value !== selectedValue) {
      onChange?.({ target: { value: option.value } });
    }
  };

  const moveActive = (delta: 1 | -1) => {
    if (options.length === 0) return;
    let next = activeIndex;
    for (let step = 0; step < options.length; step += 1) {
      next = (next + delta + options.length) % options.length;
      if (!options[next].disabled) {
        setActiveIndex(next);
        return;
      }
    }
  };

  const handleTriggerKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (disabled) return;
    if (!open) {
      if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault();
        openPanel();
      }
      return;
    }
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        moveActive(1);
        break;
      case 'ArrowUp':
        event.preventDefault();
        moveActive(-1);
        break;
      case 'Enter':
      case ' ':
        event.preventDefault();
        if (options[activeIndex]) selectOption(options[activeIndex]);
        break;
      case 'Escape':
        event.preventDefault();
        close();
        break;
      case 'Tab':
        close();
        break;
      default:
        break;
    }
  };

  const selectedOption = selectedIndex >= 0 ? options[selectedIndex] : null;

  return (
    <div className={`${styles.wrapper} ${className}`.trim()}>
      <button
        ref={triggerRef}
        type="button"
        disabled={disabled}
        title={title}
        aria-label={ariaLabel}
        aria-haspopup="listbox"
        aria-expanded={open}
        onClick={() => (open ? close() : openPanel())}
        onKeyDown={handleTriggerKeyDown}
        className={`${BASE_TRIGGER_CLASS} ${styles.trigger}`}
      >
        <span className={`flex-1 truncate ${selectedOption ? '' : 'text-slate-400 dark:text-slate-500'}`}>
          {selectedOption?.label ?? ''}
        </span>
        <ChevronDown
          className={`shrink-0 text-slate-400 transition-transform duration-150 ${styles.icon} ${
            open ? 'rotate-180' : ''
          }`}
        />
      </button>

      {createPortal(
        <AnimatePresence>
          {open && panelRect && (
            <motion.div
              ref={panelRef}
              initial={{ opacity: 0, y: -4, scale: 0.98 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: -4, scale: 0.98 }}
              transition={{ duration: 0.12 }}
              style={{
                position: 'fixed',
                top: panelRect.top,
                left: panelRect.left,
                minWidth: panelRect.width,
                maxWidth: '20rem',
              }}
              className={`${DROPDOWN_PANEL_CLASS} origin-top`}
            >
              <ul role="listbox" className={DROPDOWN_LIST_CLASS}>
                {options.map((option, index) => {
                  const isSelected = option.value === selectedValue;
                  return (
                    <li key={`${option.value}-${index}`}>
                      <button
                        type="button"
                        role="option"
                        aria-selected={isSelected}
                        disabled={option.disabled}
                        onClick={() => selectOption(option)}
                        onMouseEnter={() => setActiveIndex(index)}
                        className={dropdownOptionClass({
                          active: index === activeIndex,
                          selected: isSelected,
                          disabled: option.disabled,
                        })}
                      >
                        <span className="flex-1 truncate">{option.label}</span>
                        {isSelected && <Check className="h-3.5 w-3.5 shrink-0" />}
                      </button>
                    </li>
                  );
                })}
                {options.length === 0 && (
                  <li className="px-3 py-2 text-xs text-slate-400 dark:text-slate-500">暂无可选项</li>
                )}
              </ul>
            </motion.div>
          )}
        </AnimatePresence>,
        document.body
      )}
    </div>
  );
}

import {createContext, useContext, useEffect, useRef, type ComponentType} from 'react';

export interface MobileTopBarAction {
  key: string;
  /** 按钮提示文字（长按提示 / 无障碍朗读） */
  title: string;
  icon: ComponentType<{ className?: string; strokeWidth?: string | number }>;
  onClick: () => void;
}

export interface MobileTopBarConfig {
  /** 顶栏右侧的圆形主操作（每页最多一个，如「+」新建） */
  primary?: { title: string; onClick: () => void };
  /** 顶栏右侧的图标次操作（排在主操作左侧） */
  actions?: MobileTopBarAction[];
  /** 顶栏左侧动作（汉堡右侧，如「对话历史」） */
  leading?: MobileTopBarAction[];
  /** 顶栏中间的标题，传入即显示（可点，如点开重命名会话） */
  title?: string;
  onTitleClick?: () => void;
}

type RegisterMobileTopBar = (config: MobileTopBarConfig | null) => void;

/**
 * 移动端顶栏注册通道。
 * Layout 负责渲染顶栏骨架（☰ + 左侧动作 + 中间标题 + 右侧次操作 + 圆形主操作），
 * 页面通过 useMobileTopBar 声明「本页要往顶栏放什么」，组件卸载自动注销。
 * 回调每次渲染都可能变化，统一用 ref 兜住，只有「形状」（标题、按钮 key）变化才重新注册。
 */
export const MobileTopBarActionContext = createContext<RegisterMobileTopBar | null>(null);

export function useMobileTopBar(config: MobileTopBarConfig) {
  const register = useContext(MobileTopBarActionContext);
  const configRef = useRef(config);

  useEffect(() => {
    configRef.current = config;
  });

  const {title, onTitleClick, primary} = config;
  const leadingKeys = (config.leading ?? []).map((action) => action.key).join('|');
  const actionKeys = (config.actions ?? []).map((action) => action.key).join('|');
  const primaryTitle = primary?.title ?? '';

  useEffect(() => {
    if (!register) return;
    // 从 ref 读取最新值：回调与数组每次渲染都是新引用，放进依赖会导致无限重注册
    register({
      leading: configRef.current.leading,
      actions: configRef.current.actions,
      title: configRef.current.title,
      onTitleClick: configRef.current.onTitleClick,
      primary: configRef.current.primary,
    });
    return () => register(null);
  }, [register, title, onTitleClick, leadingKeys, actionKeys, primaryTitle]);
}

/** 只注册右侧圆形「+」主操作的简写（多数页面只用它） */
export function useMobileTopBarAction(title: string, onClick: () => void) {
  const onClickRef = useRef(onClick);

  useEffect(() => {
    onClickRef.current = onClick;
  }, [onClick]);

  useMobileTopBar({primary: {title, onClick: () => onClickRef.current()}});
}

import {createContext, useContext, useEffect, useRef} from 'react';

export interface MobileTopBarAction {
  /** 按钮提示文字（长按提示 / 无障碍朗读） */
  title: string;
  onClick: () => void;
}

type RegisterMobileTopBarAction = (action: MobileTopBarAction | null) => void;

/**
 * 移动端顶栏动作注册通道。
 * Layout 负责渲染顶栏右侧的圆形「+」，页面通过 useMobileTopBarAction 把
 * 当前页面的「新建」动作注册进来；组件卸载时自动注销，按钮随之消失。
 */
export const MobileTopBarActionContext = createContext<RegisterMobileTopBarAction | null>(null);

export function useMobileTopBarAction(title: string, onClick: () => void) {
  const register = useContext(MobileTopBarActionContext);
  const onClickRef = useRef(onClick);

  // 回调可能每次渲染都变化，用 ref 兜住，注册只在页面挂载时发生一次
  useEffect(() => {
    onClickRef.current = onClick;
  }, [onClick]);

  useEffect(() => {
    if (!register) return;
    register({title, onClick: () => onClickRef.current()});
    return () => register(null);
  }, [register, title]);
}

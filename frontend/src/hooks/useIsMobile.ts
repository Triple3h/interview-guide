import { useEffect, useState } from 'react';

/** 与 Tailwind 的 md 断点（768px）保持一致：小于该宽度视为移动端 */
const MOBILE_QUERY = '(max-width: 767px)';

/**
 * 移动端视口判断。
 * 只用于「必须改变渲染结果」的场景（如落地路由分流、组件树切换）；
 * 纯样式差异一律用 CSS 断点类（hidden md:flex 等），不要调用本 hook。
 */
export function useIsMobile(): boolean {
  const [isMobile, setIsMobile] = useState<boolean>(() => window.matchMedia(MOBILE_QUERY).matches);

  useEffect(() => {
    const query = window.matchMedia(MOBILE_QUERY);
    const handleChange = (event: MediaQueryListEvent) => setIsMobile(event.matches);
    // 订阅前再同步一次，覆盖首次渲染到 effect 执行之间设备状态发生变化的窗口
    setIsMobile(query.matches);
    query.addEventListener('change', handleChange);
    return () => query.removeEventListener('change', handleChange);
  }, []);

  return isMobile;
}

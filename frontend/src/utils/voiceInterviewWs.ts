/**
 * 语音面试 WebSocket URL 构建
 *
 * 后端不再返回 webSocketUrl（原先硬编码 ws://localhost:8080，远程部署连不上）；
 * 浏览器 WebSocket 无法自定义请求头，因此登录 token 走查询参数 ?token=xxx，
 * 由后端 VoiceInterviewHandshakeInterceptor 校验（P2 落地）。
 *
 * 本模块保持零运行时依赖（纯函数），便于 node --test 直接单测；
 * token 由调用方（页面）从 auth/tokenStore 读取后传入。
 */

export interface VoiceInterviewWsContext {
  /** window.location.protocol */
  protocol?: string;
  /** window.location.host（含端口） */
  host?: string;
  /** VITE_WS_BASE_URL 覆盖（如 wss://api.example.com）；缺省按当前站点推导 */
  wsBaseUrl?: string;
  /** 学员登录 token */
  token?: string | null;
}

export function buildVoiceInterviewWsUrl(
  sessionId: number,
  context: VoiceInterviewWsContext = {},
): string {
  const base = resolveWsBase(context);
  const tokenQuery = context.token ? `?token=${encodeURIComponent(context.token)}` : '';
  return `${base}/ws/voice-interview/${sessionId}${tokenQuery}`;
}

function resolveWsBase(context: VoiceInterviewWsContext): string {
  const override = context.wsBaseUrl?.trim();
  if (override) {
    return override.replace(/\/+$/, '');
  }
  const protocol = context.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${context.host ?? ''}`;
}

/** 浏览器运行时入口：按当前站点地址拼 URL，token 由调用方传入 */
export function voiceInterviewWsUrl(sessionId: number, token?: string | null): string {
  return buildVoiceInterviewWsUrl(sessionId, {
    protocol: window.location.protocol,
    host: window.location.host,
    wsBaseUrl: import.meta.env.VITE_WS_BASE_URL,
    token,
  });
}

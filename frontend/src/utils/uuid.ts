// 生成 v4 UUID。
// crypto.randomUUID 仅在安全上下文（HTTPS / localhost）可用，
// 通过局域网 IP 以 HTTP 访问时不可用，需要回退方案。
let uuidSupported: boolean | null = null;

function randomUuidSupported(): boolean {
  if (uuidSupported !== null) {
    return uuidSupported;
  }
  uuidSupported = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function';
  return uuidSupported;
}

export function uuid(): string {
  if (randomUuidSupported()) {
    return crypto.randomUUID();
  }

  // 回退：使用 crypto.getRandomValues 生成 v4 UUID
  // （crypto.getRandomValues 在非安全上下文同样可用）
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 0x0f) | 0x40; // version 4
    bytes[8] = (bytes[8] & 0x3f) | 0x80; // variant 10
    const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }

  // 最后的兜底（Math.random 场景，理论上不会走到）
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

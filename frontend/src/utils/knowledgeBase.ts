// 知识库分类展示工具：分类按斜杠分段（一级/二级/三级），名称去掉与上下文重复的分类前缀

/** 分类按斜杠分段（一级/二级/三级），去掉空段与首尾空格 */
export function categorySegments(category?: string | null): string[] {
  return (category || '')
    .split('/')
    .map(part => part.trim())
    .filter(Boolean);
}

/**
 * 去掉「分类前缀/」后的展示名：分类为 ai/agent 时，
 * ai/agent/loop-engineering → loop-engineering，loop-engineering.md → loop-engineering.md；
 * 名称不以分类为前缀（自定义命名）时原样返回，完整名称仍可通过 title 悬浮查看
 */
export function stripCategoryPrefix(category: string | null | undefined, value: string): string {
  const prefix = categorySegments(category).join('/');
  return prefix && value.startsWith(`${prefix}/`) ? value.slice(prefix.length + 1) : value;
}

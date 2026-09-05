import type {
  InterviewCategoryCapacity,
  InterviewFollowUpCapacity,
} from '../../api/knowledgebase';

export interface CategoryQuotaPreview {
  category: string;
  questionCount: number;
}

export function getSelectedCapacity(
  options: InterviewFollowUpCapacity[],
  followUpCount: number
): InterviewFollowUpCapacity | null {
  return options.find(option => option.followUpCount === followUpCount) ?? null;
}

/**
 * 模拟后端"按组轮转均衡抽题"的名额分配：每轮给每个未耗尽的组取 1 题名额，
 * 直到凑满 mainQuestionCount。用于开考前预览每个方向/知识库预计被抽到几题。
 */
export function previewCategoryQuotas(
  categories: InterviewCategoryCapacity[],
  mainQuestionCount: number
): CategoryQuotaPreview[] {
  if (mainQuestionCount <= 0 || categories.length === 0) {
    return [];
  }
  const remaining = categories.map(category => category.availableQuestionCount);
  const quotas = categories.map(() => 0);
  let need = mainQuestionCount;
  let pickedInPass = true;
  while (need > 0 && pickedInPass) {
    pickedInPass = false;
    for (let i = 0; i < categories.length && need > 0; i += 1) {
      if (remaining[i] > 0) {
        remaining[i] -= 1;
        quotas[i] += 1;
        need -= 1;
        pickedInPass = true;
      }
    }
  }
  return categories
    .map((category, index) => ({ category: category.category, questionCount: quotas[index] }))
    .filter(item => item.questionCount > 0);
}

export function formatCategoryQuotaPreview(previews: CategoryQuotaPreview[]): string {
  return previews.map(item => `${item.category} ${item.questionCount} 题`).join('、');
}

export function getStrictCapacityMessage(
  options: InterviewFollowUpCapacity[],
  followUpCount: number,
  mainQuestionCount: number
): string {
  const selected = getSelectedCapacity(options, followUpCount);
  if (!selected) {
    return '暂时无法确认当前配置的可用题数，请稍后重试。';
  }
  if (selected.selectable) {
    return `当前条件可用 ${selected.availableQuestionCount} 道主问题。`;
  }
  const selectableOptions = options
    .filter(option => option.availableQuestionCount >= mainQuestionCount);
  if (selectableOptions.length === 0) {
    return `当前仅有 ${selected.availableQuestionCount} 道题包含至少 ${followUpCount} 个追问，`
      + `无法抽取 ${mainQuestionCount} 道主问题。`
      + '当前题量下没有足够的已启用主问题，请减少主问题数或补充题库。';
  }
  const maximumStrictCount = selectableOptions
    .reduce((maximum, option) => Math.max(maximum, option.followUpCount), 0);
  return `当前仅有 ${selected.availableQuestionCount} 道题包含至少 ${followUpCount} 个追问，`
    + `无法抽取 ${mainQuestionCount} 道主问题。`
    + `在当前题量下，每题最多可严格保证 ${maximumStrictCount} 个追问。`;
}

export function getFollowUpQualityWarning(
  actualCount: number,
  targetCount?: number | null
): string | null {
  if (targetCount == null || actualCount >= targetCount) {
    return null;
  }
  return `追问不足：实际 ${actualCount} / 目标 ${targetCount}`;
}

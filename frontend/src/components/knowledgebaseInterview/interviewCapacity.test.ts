import assert from 'node:assert/strict';
import test from 'node:test';
import {
  formatCategoryQuotaPreview,
  getFollowUpQualityWarning,
  getSelectedCapacity,
  getStrictCapacityMessage,
  previewCategoryQuotas,
} from './interviewCapacity.ts';

const options = [
  { followUpCount: 0, availableQuestionCount: 10, selectable: true },
  { followUpCount: 1, availableQuestionCount: 8, selectable: true },
  { followUpCount: 2, availableQuestionCount: 5, selectable: true },
  { followUpCount: 3, availableQuestionCount: 2, selectable: false },
];

test('根据严格追问数量找到后端容量选项', () => {
  assert.deepEqual(getSelectedCapacity(options, 3), options[3]);
  assert.equal(getSelectedCapacity(options, 4), null);
});

test('容量不足提示保留用户选择并说明当前最大严格追问数', () => {
  assert.equal(
    getStrictCapacityMessage(options, 3, 5),
    '当前仅有 2 道题包含至少 3 个追问，无法抽取 5 道主问题。在当前题量下，每题最多可严格保证 2 个追问。'
  );
});

test('连零追问配置都不可用时明确提示主问题本身不足', () => {
  const insufficientOptions = options.map(option => ({
    ...option,
    availableQuestionCount: Math.min(option.availableQuestionCount, 2),
    selectable: false,
  }));

  assert.equal(
    getStrictCapacityMessage(insufficientOptions, 3, 5),
    '当前仅有 2 道题包含至少 3 个追问，无法抽取 5 道主问题。'
      + '当前题量下没有足够的已启用主问题，请减少主问题数或补充题库。'
  );
});

test('最近生成目标只对追问不足的题目显示质量警告', () => {
  assert.equal(getFollowUpQualityWarning(1, 2), '追问不足：实际 1 / 目标 2');
  assert.equal(getFollowUpQualityWarning(2, 2), null);
  assert.equal(getFollowUpQualityWarning(3, 2), null);
  assert.equal(getFollowUpQualityWarning(0, null), null);
});

test('跨组名额按轮转均衡分配且容量不足的组少分配', () => {
  const categories = [
    { category: 'Redis', availableQuestionCount: 4 },
    { category: 'MySQL', availableQuestionCount: 3 },
    { category: 'JVM', availableQuestionCount: 1 },
  ];
  assert.deepEqual(previewCategoryQuotas(categories, 5), [
    { category: 'Redis', questionCount: 2 },
    { category: 'MySQL', questionCount: 2 },
    { category: 'JVM', questionCount: 1 },
  ]);
  // 题量不足的组把名额让给其他组
  assert.deepEqual(previewCategoryQuotas(categories, 8), [
    { category: 'Redis', questionCount: 4 },
    { category: 'MySQL', questionCount: 3 },
    { category: 'JVM', questionCount: 1 },
  ]);
});

test('题量少于组数时名额优先给题量多的组', () => {
  const categories = [
    { category: 'Redis', availableQuestionCount: 3 },
    { category: 'MySQL', availableQuestionCount: 2 },
    { category: 'JVM', availableQuestionCount: 1 },
  ];
  assert.deepEqual(previewCategoryQuotas(categories, 2), [
    { category: 'Redis', questionCount: 1 },
    { category: 'MySQL', questionCount: 1 },
  ]);
});

test('名额为 0 的组不返回，入参非法时返回空数组', () => {
  const categories = [
    { category: 'Redis', availableQuestionCount: 2 },
    { category: 'JVM', availableQuestionCount: 0 },
  ];
  assert.deepEqual(previewCategoryQuotas(categories, 1), [
    { category: 'Redis', questionCount: 1 },
  ]);
  assert.deepEqual(previewCategoryQuotas([], 5), []);
  assert.deepEqual(previewCategoryQuotas(categories, 0), []);
});

test('名额预览格式化为可读文案', () => {
  assert.equal(
    formatCategoryQuotaPreview([
      { category: 'Redis', questionCount: 2 },
      { category: 'MySQL', questionCount: 1 },
    ]),
    'Redis 2 题、MySQL 1 题'
  );
  assert.equal(formatCategoryQuotaPreview([]), '');
});

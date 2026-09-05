# Task
请为知识库《{knowledgeBaseName}》生成 {questionCount} 道面试题草稿。

difficulty: {difficulty}

# Existing Categories
{existingCategories}

# Category Coverage
- 将 {questionCount} 道题整体均衡分配到 # Existing Categories 列出的已有方向上，各方向题量尽量接近。
- 若 {questionCount} 少于已有方向数量，优先为已有题量最少的方向补题，此时无需强行覆盖全部方向。
- 仅当知识库内容明显超出已有方向覆盖范围时才新增方向；新增方向不超过 {categoryLimit} 个。

# Existing Questions
以下题目已经出过，请从不同知识点或不同考察角度出题，避免题面或考察点重复：
{existingQuestions}

# Knowledge Base Context
{context}

# Output Requirements
- category: 面试方向，遵循 # Category Coverage 的分配规则
- question: 主问题题干
- type: 可选的技术分类 key，例如 JVM、REDIS、SPRING；不需要时可留空
- topicSummary: 不超过 80 字的知识点摘要
- referenceAnswer: 参考答案
- keyPoints: 评分要点列表
- scoringRubric: 10 分制评分规则
- followUps: 每道主问题配 {followUpCount} 个追问，每个追问包含 question、referenceAnswer、keyPoints、scoringRubric；若 {followUpCount} 为 0 则返回空数组

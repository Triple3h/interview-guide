package interview.guide.modules.knowledgebase.model;

/**
 * 批量删除知识库结果
 *
 * @param successCount 成功删除的数量
 * @param failedCount  删除失败的数量（逐条隔离，单条失败不影响其余）
 */
public record BatchDeleteKnowledgeBaseResult(int successCount, int failedCount) {
}

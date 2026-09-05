package interview.guide.modules.knowledgebase.model;

/**
 * 批量生成题目的单库提交结果。
 */
public record KnowledgeBaseBatchGenerateResultItem(
    Long knowledgeBaseId,
    boolean submitted,
    String message
) {
}

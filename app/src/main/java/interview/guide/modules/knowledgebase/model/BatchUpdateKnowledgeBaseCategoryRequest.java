package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量更新知识库分类请求
 */
public record BatchUpdateKnowledgeBaseCategoryRequest(
    @NotEmpty(message = "请选择要更新的知识库") List<Long> ids,
    String category) {
}

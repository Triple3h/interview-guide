package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量删除知识库请求
 */
public record BatchDeleteKnowledgeBaseRequest(
    @NotEmpty(message = "请选择要删除的知识库") List<Long> ids) {
}

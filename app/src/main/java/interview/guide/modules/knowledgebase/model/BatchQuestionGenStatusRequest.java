package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量查询题目生成状态：用于前端展示一次批量提交的生成队列进度。
 */
public record BatchQuestionGenStatusRequest(
    @NotEmpty(message = "请至少选择一个知识库")
    @Size(max = 200, message = "一次最多查询200个知识库")
    List<Long> knowledgeBaseIds
) {
}

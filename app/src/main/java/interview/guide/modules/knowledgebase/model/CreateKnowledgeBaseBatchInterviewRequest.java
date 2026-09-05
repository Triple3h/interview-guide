package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 跨知识库整体开始面试：从所选知识库的已启用题目中按知识库均衡抽题，同库题目连续作答。
 */
public record CreateKnowledgeBaseBatchInterviewRequest(
    @NotEmpty(message = "请至少选择一个知识库")
    @Size(max = 200, message = "一次最多选择200个知识库")
    List<Long> knowledgeBaseIds,
    String difficulty,
    @Min(value = 1, message = "主问题数量最少1题")
    @Max(value = 20, message = "主问题数量最多20题")
    int mainQuestionCount,
    @Min(value = 0, message = "追问数量不能小于0")
    @Max(value = 5, message = "每题追问最多5个")
    int followUpCount,
    String llmProvider
) {
}

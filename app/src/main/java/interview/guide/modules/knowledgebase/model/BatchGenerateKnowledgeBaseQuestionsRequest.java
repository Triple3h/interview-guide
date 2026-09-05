package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量生成题目：为所选知识库分别提交一个异步生成任务（每个知识库独立替换自身题库）。
 */
public record BatchGenerateKnowledgeBaseQuestionsRequest(
    @NotEmpty(message = "请至少选择一个知识库")
    @Size(max = 200, message = "一次最多选择200个知识库")
    List<Long> knowledgeBaseIds,
    @Pattern(regexp = "junior|mid|senior", message = "题目难度不合法")
    String difficulty,
    @Min(value = 1, message = "题目数量最少1题")
    @Max(value = 30, message = "题目数量最多30题")
    int questionCount,
    @Min(value = 0, message = "追问数量不能小于0")
    @Max(value = 5, message = "每题追问最多5个")
    Integer followUpCount,
    @Min(value = 1, message = "方向数最少1个")
    @Max(value = 5, message = "方向数最多5个")
    @NotNull(message = "方向数量不能为空")
    Integer categoryLimit,
    @Size(max = 64, message = "模型提供商标识过长")
    String llmProvider
) {
}

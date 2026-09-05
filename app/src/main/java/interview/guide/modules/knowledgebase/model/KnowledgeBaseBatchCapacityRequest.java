package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 跨知识库面试容量查询：统计所选知识库在指定条件下的可用题目与各库名额分布。
 */
public record KnowledgeBaseBatchCapacityRequest(
    @NotEmpty(message = "请至少选择一个知识库")
    @Size(max = 50, message = "一次最多选择50个知识库")
    List<Long> knowledgeBaseIds,
    String difficulty,
    @Min(value = 1, message = "主问题数量最少1题")
    @Max(value = 20, message = "主问题数量最多20题")
    int mainQuestionCount,
    @Min(value = 0, message = "追问数量不能小于0")
    @Max(value = 5, message = "每题追问最多5个")
    int followUpCount
) {
}

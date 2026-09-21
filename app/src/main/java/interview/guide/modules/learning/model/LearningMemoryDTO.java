package interview.guide.modules.learning.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 个人记忆 DTO
 */
public class LearningMemoryDTO {

    public record CreateMemoryRequest(
        @NotBlank(message = "记忆类型不能为空")
        String kind,

        @NotBlank(message = "记忆内容不能为空")
        @Size(max = 500, message = "记忆内容最长 500 字")
        String content
    ) {}

    /**
     * 更新记忆（null 字段不修改）
     */
    public record UpdateMemoryRequest(
        String kind,

        @Size(max = 500, message = "记忆内容最长 500 字")
        String content
    ) {}

    public record LearningMemoryResponse(
        Long id,
        String kind,
        String kindLabel,
        String content,
        Long sourceSessionId,
        Long sourceMessageId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    /**
     * 抽取模型的结构化输出：对本轮对话要执行的记忆操作
     */
    public record ExtractResult(List<MemoryOp> operations) {}

    /**
     * 单条抽取操作。ADD 不需要 id；UPDATE/DELETE 的 id 必须是已有记忆。
     */
    public record MemoryOp(String action, Long id, String kind, String content) {}
}

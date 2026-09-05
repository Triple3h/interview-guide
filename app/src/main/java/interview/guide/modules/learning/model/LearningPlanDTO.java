package interview.guide.modules.learning.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 学习计划 DTO
 */
public class LearningPlanDTO {

    /**
     * 手动新增计划条目
     */
    public record CreatePlanItemRequest(
        @NotBlank(message = "计划主题不能为空")
        @Size(max = 200, message = "主题最长 200 字")
        String topic,

        @Size(max = 500, message = "学习目标最长 500 字")
        String goal
    ) {}

    /**
     * 更新计划条目（null 字段不修改）
     */
    public record UpdatePlanItemRequest(
        @Size(max = 200, message = "主题最长 200 字")
        String topic,

        @Size(max = 500, message = "学习目标最长 500 字")
        String goal,

        String status
    ) {}

    /**
     * 仅更新状态
     */
    public record UpdatePlanStatusRequest(
        @NotBlank(message = "状态不能为空")
        String status
    ) {}

    /**
     * 计划条目响应
     */
    public record PlanItemResponse(
        Long id,
        String topic,
        String goal,
        String status,
        String statusLabel,
        int sortOrder,
        Long sourceSessionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}
}

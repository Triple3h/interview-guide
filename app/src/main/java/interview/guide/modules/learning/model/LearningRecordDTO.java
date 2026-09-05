package interview.guide.modules.learning.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 学习记录 DTO
 */
public class LearningRecordDTO {

    /**
     * 手动新增学习记录
     */
    public record CreateRecordRequest(
        @NotBlank(message = "知识点主题不能为空")
        @Size(max = 200, message = "主题最长 200 字")
        String topic,

        @NotBlank(message = "学习总结不能为空")
        String summary,

        @NotNull(message = "掌握度不能为空")
        String mastery
    ) {}

    /**
     * 更新学习记录（null 字段不修改）
     */
    public record UpdateRecordRequest(
        @Size(max = 200, message = "主题最长 200 字")
        String topic,

        String summary,

        String mastery
    ) {}

    /**
     * 学习记录响应
     */
    public record LearningRecordResponse(
        Long id,
        String topic,
        String summary,
        String mastery,
        String masteryLabel,
        Long sourceSessionId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime lastReviewedAt
    ) {}
}

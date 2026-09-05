package interview.guide.modules.learning.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 学习帮手 Agent 相关 DTO
 */
public class LearningAgentDTO {

    /**
     * 创建学习会话请求（标题可选，为空则默认）
     */
    public record CreateLearningSessionRequest(
        @Size(max = 100, message = "标题最长 100 字")
        String title
    ) {}

    /**
     * 学习会话消息请求
     */
    public record LearningAgentChatRequest(
        @NotBlank(message = "问题不能为空")
        String question
    ) {}
}

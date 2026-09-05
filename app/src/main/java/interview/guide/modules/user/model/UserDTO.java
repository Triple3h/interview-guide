package interview.guide.modules.user.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 用户相关 DTO
 */
public class UserDTO {

    /**
     * 创建成员请求（昵称必填，其余选填）
     */
    public record CreateUserRequest(
        @NotBlank(message = "昵称不能为空")
        @Size(max = 50, message = "昵称最长 50 字")
        String nickname,

        @Size(max = 8, message = "头像 Emoji 过长")
        String avatarEmoji,

        @Size(max = 100, message = "职业最长 100 字")
        String occupation,

        @Size(max = 100, message = "学习方向最长 100 字")
        String learningDirection,

        @Size(max = 200, message = "当前水平最长 200 字")
        String currentLevel,

        @Size(max = 500, message = "学习目标最长 500 字")
        String learningGoal
    ) {}

    /**
     * 更新资料请求（全部选填，null 表示不修改）
     */
    public record UpdateUserRequest(
        @Size(max = 50, message = "昵称最长 50 字")
        String nickname,

        @Size(max = 8, message = "头像 Emoji 过长")
        String avatarEmoji,

        @Size(max = 100, message = "职业最长 100 字")
        String occupation,

        @Size(max = 100, message = "学习方向最长 100 字")
        String learningDirection,

        @Size(max = 200, message = "当前水平最长 200 字")
        String currentLevel,

        @Size(max = 500, message = "学习目标最长 500 字")
        String learningGoal
    ) {}

    /**
     * 用户响应
     */
    public record UserResponse(
        Long id,
        String nickname,
        String avatarEmoji,
        String occupation,
        String learningDirection,
        String currentLevel,
        String learningGoal,
        LocalDateTime createdAt
    ) {}
}

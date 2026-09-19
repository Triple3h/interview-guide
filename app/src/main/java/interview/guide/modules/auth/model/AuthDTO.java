package interview.guide.modules.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 鉴权相关 DTO：学员登录 / 管理端 Token 登录 / 改密
 */
public class AuthDTO {

    /**
     * 学员登录请求
     */
    public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 50, message = "用户名最长 50 字")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(max = 100, message = "密码过长")
        String password
    ) {}

    /**
     * 学员登录响应：token + 资料摘要
     */
    public record LoginResponse(
        String token,
        String tokenName,
        UserProfile profile
    ) {}

    /**
     * 当前登录账号资料摘要（含角色，前端据此决定菜单与后台入口）
     */
    public record UserProfile(
        Long id,
        String username,
        String nickname,
        String avatarEmoji,
        String role,
        String roleLabel
    ) {}

    /**
     * 修改密码请求
     */
    public record ChangePasswordRequest(
        @NotBlank(message = "原密码不能为空")
        String oldPassword,

        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 64, message = "新密码长度需为 6-64 位")
        String newPassword
    ) {}

}

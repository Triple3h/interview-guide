package interview.guide.modules.user.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端用户管理相关 DTO
 */
public class AdminUserDTO {

  /**
   * 用户分页结果（管理端列表）
   *
   * @param items 当前页数据
   * @param total 过滤后的总条数
   * @param page  当前页码（从 0 开始）
   * @param size  实际生效的每页条数
   */
  public record UserPageResponse(
      List<AdminUserResponse> items,
      long total,
      int page,
      int size
  ) {}

  /**
   * 管理端用户视图：在学员资料基础上补充角色与状态，供后台分配权限
   */
  public record AdminUserResponse(
      Long id,
      String username,
      String nickname,
      String avatarEmoji,
      String status,
      String role,
      String roleLabel,
      String occupation,
      String learningDirection,
      String currentLevel,
      String learningGoal,
      LocalDateTime createdAt,
      LocalDateTime lastLoginAt
  ) {}

  /**
   * 新建用户：登录账号与初始密码必填，角色默认普通用户
   */
  public record CreateUserRequest(
      @NotBlank(message = "昵称不能为空")
      @Size(max = 50, message = "昵称最长 50 字")
      String nickname,

      @NotBlank(message = "登录账号不能为空")
      @Size(max = 50, message = "登录账号最长 50 字")
      String username,

      @NotBlank(message = "初始密码不能为空")
      @Size(min = 6, max = 64, message = "密码长度需为 6-64 位")
      String password,

      @Size(max = 20, message = "角色编码过长")
      String role,

      @Size(max = 20, message = "状态编码过长")
      String status,

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
   * 编辑用户：全部选填，null 表示不修改
   */
  public record UpdateUserRequest(
      @Size(max = 50, message = "昵称最长 50 字")
      String nickname,

      @Size(max = 50, message = "登录账号最长 50 字")
      String username,

      @Size(max = 20, message = "角色编码过长")
      String role,

      @Size(max = 20, message = "状态编码过长")
      String status,

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
   * 重置密码请求
   */
  public record ResetPasswordRequest(
      @NotBlank(message = "新密码不能为空")
      @Size(min = 6, max = 64, message = "新密码长度需为 6-64 位")
      String newPassword
  ) {}

  /**
   * 角色选项：角色说明 + 该角色拥有的权限清单（前端据此展示权限矩阵）
   */
  public record RoleOption(
      String code,
      String label,
      String description,
      List<PermissionOption> permissions
  ) {}

  /**
   * 权限项说明
   */
  public record PermissionOption(
      String code,
      String label,
      String description
  ) {}
}

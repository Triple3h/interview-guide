package interview.guide.modules.user.model;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;

import java.util.List;
import java.util.Locale;

/**
 * 用户角色：决定账号在系统中的权限集合
 *
 * <p>超级管理员是系统最高权限，可管理其他用户的角色与权限；
 * 管理员可管理知识库与普通用户资料；普通用户只使用学习功能。</p>
 */
public enum UserRole {

  SUPER_ADMIN("超级管理员", "系统最高权限，可管理用户、角色与权限"),
  ADMIN("管理员", "可管理知识库与普通用户资料"),
  USER("普通用户", "可使用模拟面试、语音面试与学习帮手");

  private final String label;
  private final String description;

  UserRole(String label, String description) {
    this.label = label;
    this.description = description;
  }

  public String getLabel() {
    return label;
  }

  public String getDescription() {
    return description;
  }

  /** 该角色拥有的权限集合 */
  public List<Permission> permissions() {
    return switch (this) {
      case SUPER_ADMIN -> Permission.all();
      case ADMIN -> List.of(
        Permission.INTERVIEW_USE,
        Permission.VOICE_USE,
        Permission.LEARNING_USE,
        Permission.KNOWLEDGE_BASE_MANAGE,
        Permission.USER_VIEW,
        Permission.USER_MANAGE);
      case USER -> List.of(
        Permission.INTERVIEW_USE,
        Permission.VOICE_USE,
        Permission.LEARNING_USE);
    };
  }

  public boolean hasPermission(Permission permission) {
    return permission != null && permissions().contains(permission);
  }

  /** 是否具备后台管理资格（可登录管理后台） */
  public boolean isAdmin() {
    return this == SUPER_ADMIN || this == ADMIN;
  }

  /**
   * 解析角色编码：空白按 {@link #USER} 处理，非法值转业务异常
   */
  public static UserRole fromCode(String code) {
    if (code == null || code.isBlank()) {
      return USER;
    }
    try {
      return UserRole.valueOf(code.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的角色: " + code);
    }
  }
}

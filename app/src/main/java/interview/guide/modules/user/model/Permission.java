package interview.guide.modules.user.model;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 权限项：系统内可被角色授予的最小能力单元
 *
 * <p>角色与权限是「角色决定权限集合」的关系：管理端调整用户角色即完成权限授予与收回，
 * 因此权限本身不单独落库，避免角色与权限两套真源不一致。</p>
 */
public enum Permission {

  INTERVIEW_USE("interview:use", "模拟面试", "发起模拟面试并查看面试记录与报告"),
  VOICE_USE("voice:use", "语音面试", "使用语音面试并查看评估报告"),
  LEARNING_USE("learning:use", "学习帮手", "使用学习帮手、学习计划、学习台账与个人记忆"),
  KNOWLEDGE_BASE_MANAGE("knowledgebase:manage", "知识库管理", "上传与删除知识库、维护题库"),
  USER_VIEW("user:view", "查看用户", "查看后台用户列表与角色权限说明"),
  USER_MANAGE("user:manage", "管理用户", "新建用户、编辑资料、启用或禁用账号"),
  USER_ROLE("user:role", "分配角色与权限", "调整用户角色、重置密码、删除用户");

  private final String code;
  private final String label;
  private final String description;

  Permission(String code, String label, String description) {
    this.code = code;
    this.label = label;
    this.description = description;
  }

  public String getCode() {
    return code;
  }

  public String getLabel() {
    return label;
  }

  public String getDescription() {
    return description;
  }

  public static List<Permission> all() {
    return Arrays.asList(values());
  }

  public static Permission fromCode(String code) {
    String normalized = code == null ? "" : code.trim();
    for (Permission permission : values()) {
      if (permission.code.equalsIgnoreCase(normalized)) {
        return permission;
      }
    }
    return null;
  }

  /** 与 {@link UserRole#fromCode} 保持一致的解析风格，便于上层统一转业务异常 */
  public static Permission require(String code) {
    Permission permission = fromCode(code);
    if (permission == null) {
      throw new IllegalArgumentException("无效的权限编码: " + code);
    }
    return permission;
  }

  public static String normalize(String code) {
    return code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
  }
}

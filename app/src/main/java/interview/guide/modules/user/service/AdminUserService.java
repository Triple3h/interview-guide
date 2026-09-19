package interview.guide.modules.user.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.UserMapper;
import interview.guide.modules.auth.service.AdminOperatorService;
import interview.guide.modules.auth.StpUserUtil;
import interview.guide.modules.user.model.AdminUserDTO;
import interview.guide.modules.user.model.AdminUserDTO.AdminUserResponse;
import interview.guide.modules.user.model.AdminUserDTO.CreateUserRequest;
import interview.guide.modules.user.model.AdminUserDTO.PermissionOption;
import interview.guide.modules.user.model.AdminUserDTO.RoleOption;
import interview.guide.modules.user.model.AdminUserDTO.UpdateUserRequest;
import interview.guide.modules.user.model.Permission;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.model.UserRole;
import interview.guide.modules.user.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 管理端用户服务：查看 / 新增 / 编辑 / 删除用户，以及分配角色与权限
 *
 * <p>权限由角色派生（见 {@link UserRole#permissions()}），调整角色即完成权限授予与收回；
 * 授予管理员及以上角色、删除用户、重置密码属于高权限操作，仅超级管理员可执行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

  private static final int MAX_PAGE_SIZE = 100;

  private final UserRepository userRepository;
  private final UserMapper userMapper;
  private final PasswordEncoder passwordEncoder;
  private final AdminOperatorService adminOperatorService;

  /**
   * 分页查询用户（关键字命中昵称或登录账号）
   */
  @Transactional(readOnly = true)
  public AdminUserDTO.UserPageResponse page(String keyword, String role, String status, int page, int size) {
    adminOperatorService.requirePermission(Permission.USER_VIEW);

    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int safePage = Math.max(page, 0);
    Page<UserEntity> result = userRepository.findAll(
        buildSpecification(keyword, role, status),
        PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "id")));

    return new AdminUserDTO.UserPageResponse(
        userMapper.toAdminResponseList(result.getContent()),
        result.getTotalElements(),
        safePage,
        safeSize);
  }

  /**
   * 角色与权限目录：前端据此展示每个角色拥有的权限
   */
  @Transactional(readOnly = true)
  public List<RoleOption> roleCatalog() {
    adminOperatorService.requirePermission(Permission.USER_VIEW);
    return Arrays.stream(UserRole.values())
        .map(role -> new RoleOption(
            role.name(),
            role.getLabel(),
            role.getDescription(),
            role.permissions().stream()
                .map(permission -> new PermissionOption(
                    permission.getCode(), permission.getLabel(), permission.getDescription()))
                .toList()))
        .toList();
  }

  /**
   * 新建用户：登录账号与初始密码必填
   */
  @Transactional
  public AdminUserResponse create(CreateUserRequest request) {
    adminOperatorService.requirePermission(Permission.USER_MANAGE);

    String nickname = requireText(request.nickname(), "昵称不能为空", 50);
    String username = normalizeUsername(request.username());
    if (username == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "登录账号不能为空");
    }
    if (request.password() == null || request.password().isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "初始密码不能为空");
    }
    checkNicknameAvailable(nickname, null);
    checkUsernameAvailable(username, null);

    UserEntity entity = new UserEntity();
    entity.setNickname(nickname);
    entity.setUsername(username);
    entity.setPasswordHash(passwordEncoder.encode(request.password().trim()));
    entity.setRole(resolveRole(request.role(), UserRole.USER));
    entity.setStatus(resolveStatus(request.status(), UserEntity.STATUS_ACTIVE));
    applyProfile(entity, request.avatarEmoji(), request.occupation(),
        request.learningDirection(), request.currentLevel(), request.learningGoal());

    UserEntity saved = userRepository.save(entity);
    log.info("后台新建用户: id={}, username={}, role={}", saved.getId(), username, saved.getRole());
    return userMapper.toAdminResponse(saved);
  }

  /**
   * 编辑用户资料 / 角色 / 状态（null 字段不修改）
   */
  @Transactional
  public AdminUserResponse update(Long id, UpdateUserRequest request) {
    adminOperatorService.requirePermission(Permission.USER_MANAGE);

    UserEntity entity = getEntity(id);

    if (request.nickname() != null && !request.nickname().isBlank()) {
      String nickname = request.nickname().trim();
      checkNicknameAvailable(nickname, id);
      entity.setNickname(nickname);
    }

    if (request.username() != null) {
      String username = normalizeUsername(request.username());
      checkUsernameAvailable(username, id);
      entity.setUsername(username);
    }

    UserRole newRole = resolveRole(request.role(), entity.getRole());
    String newStatus = resolveStatus(request.status(), entity.getStatus());
    guardLastSuperAdmin(entity, newRole, newStatus);
    entity.setRole(newRole);
    entity.setStatus(newStatus);

    applyProfile(entity, request.avatarEmoji(), request.occupation(),
        request.learningDirection(), request.currentLevel(), request.learningGoal());

    // 禁用后立即失效其学员端登录态
    if (UserEntity.STATUS_DISABLED.equals(newStatus)) {
      kickout(entity.getId());
    }

    log.info("后台编辑用户: id={}, role={}, status={}", id, newRole, newStatus);
    return userMapper.toAdminResponse(userRepository.save(entity));
  }

  /**
   * 删除用户：系统必须保留至少一个超级管理员
   */
  @Transactional
  public void delete(Long id) {
    adminOperatorService.requirePermission(Permission.USER_ROLE);

    UserEntity entity = getEntity(id);
    if (entity.getRole() == UserRole.SUPER_ADMIN && userRepository.countByRole(UserRole.SUPER_ADMIN) <= 1) {
      throw new BusinessException(ErrorCode.USER_LAST_SUPER_ADMIN,
          "至少保留一个超级管理员，请先为其他账号授予超级管理员角色");
    }

    userRepository.delete(entity);
    kickout(entity.getId());
    log.info("后台删除用户: id={}, username={}", id, entity.getUsername());
  }

  /**
   * 重置密码：重置后强制该账号重新登录
   */
  @Transactional
  public void resetPassword(Long id, String newPassword) {
    adminOperatorService.requirePermission(Permission.USER_ROLE);

    if (newPassword == null || newPassword.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "新密码不能为空");
    }
    if (newPassword.trim().length() < 6 || newPassword.trim().length() > 64) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "新密码长度需为 6-64 位");
    }

    UserEntity entity = getEntity(id);
    entity.setPasswordHash(passwordEncoder.encode(newPassword.trim()));
    userRepository.save(entity);
    kickout(entity.getId());
    log.info("后台重置用户密码: id={}", id);
  }

  public UserEntity getEntity(Long id) {
    return userRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
  }

  /**
   * 解析角色：授予管理员及以上角色属于高权限操作，仅超级管理员可执行
   */
  private UserRole resolveRole(String role, UserRole fallback) {
    UserRole resolved = role == null || role.isBlank() ? fallback : UserRole.fromCode(role);
    if (resolved != UserRole.USER) {
      adminOperatorService.requirePermission(Permission.USER_ROLE);
    }
    return resolved;
  }

  private String resolveStatus(String status, String fallback) {
    if (status == null || status.isBlank()) {
      return fallback;
    }
    String normalized = status.trim().toUpperCase(Locale.ROOT);
    if (!UserEntity.STATUS_ACTIVE.equals(normalized) && !UserEntity.STATUS_DISABLED.equals(normalized)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的账号状态: " + status);
    }
    return normalized;
  }

  /**
   * 不允许把最后一个「启用的超级管理员」降级或禁用
   */
  private void guardLastSuperAdmin(UserEntity entity, UserRole newRole, String newStatus) {
    boolean stillSuperAdmin = newRole == UserRole.SUPER_ADMIN
        && UserEntity.STATUS_ACTIVE.equals(newStatus);
    if (stillSuperAdmin || entity.getRole() != UserRole.SUPER_ADMIN) {
      return;
    }
    if (userRepository.countByRole(UserRole.SUPER_ADMIN) <= 1) {
      throw new BusinessException(ErrorCode.USER_LAST_SUPER_ADMIN,
          "至少保留一个超级管理员，请先为其他账号授予超级管理员角色");
    }
  }

  private void checkNicknameAvailable(String nickname, Long excludeId) {
    userRepository.findByNickname(nickname)
        .filter(other -> excludeId == null || !other.getId().equals(excludeId))
        .ifPresent(other -> {
          throw new BusinessException(ErrorCode.USER_NICKNAME_DUPLICATED);
        });
  }

  private void checkUsernameAvailable(String username, Long excludeId) {
    if (username == null) {
      return;
    }
    userRepository.findByUsername(username)
        .filter(other -> excludeId == null || !other.getId().equals(excludeId))
        .ifPresent(other -> {
          throw new BusinessException(ErrorCode.USER_USERNAME_DUPLICATED);
        });
  }

  private String normalizeUsername(String username) {
    if (username == null) {
      return null;
    }
    String trimmed = username.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private String requireText(String value, String message, int maxLength) {
    if (value == null || value.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
    String trimmed = value.trim();
    return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
  }

  private void applyProfile(UserEntity entity, String avatarEmoji, String occupation,
                            String learningDirection, String currentLevel, String learningGoal) {
    if (avatarEmoji != null) {
      entity.setAvatarEmoji(normalizeOrNull(avatarEmoji, 8));
    }
    if (occupation != null) {
      entity.setOccupation(normalizeOrNull(occupation, 100));
    }
    if (learningDirection != null) {
      entity.setLearningDirection(normalizeOrNull(learningDirection, 100));
    }
    if (currentLevel != null) {
      entity.setCurrentLevel(normalizeOrNull(currentLevel, 200));
    }
    if (learningGoal != null) {
      entity.setLearningGoal(normalizeOrNull(learningGoal, 500));
    }
  }

  private String normalizeOrNull(String value, int maxLength) {
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
  }

  private Specification<UserEntity> buildSpecification(String keyword, String role, String status) {
    UserRole roleFilter = role == null || role.isBlank() ? null : UserRole.fromCode(role);
    String statusFilter = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);

    return (root, query, criteriaBuilder) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (keyword != null && !keyword.isBlank()) {
        String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        predicates.add(criteriaBuilder.or(
            criteriaBuilder.like(criteriaBuilder.lower(root.get("nickname")), pattern),
            criteriaBuilder.like(criteriaBuilder.lower(root.get("username")), pattern)));
      }
      if (roleFilter != null) {
        predicates.add(criteriaBuilder.equal(root.get("role"), roleFilter));
      }
      if (statusFilter != null) {
        predicates.add(criteriaBuilder.equal(root.get("status"), statusFilter));
      }
      return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
    };
  }

  /**
   * 让目标账号的学员端登录态失效（禁用、删除、重置密码后生效）
   */
  private void kickout(Long userId) {
    try {
      StpUserUtil.kickout(userId);
    } catch (Exception e) {
      log.warn("失效学员登录态失败: userId={}, message={}", userId, e.getMessage());
    }
  }
}

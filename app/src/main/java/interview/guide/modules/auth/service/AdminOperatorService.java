package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.StpUserUtil;
import interview.guide.modules.user.model.Permission;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.model.UserRole;
import interview.guide.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 后台操作者：识别「谁在操作」并按其角色校验权限
 *
 * <p>全站只有一套登录态（学员体系），管理员与超级管理员用同一个入口登录；
 * 后台能力由角色决定，因此这里直接从登录态取账号、读角色。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminOperatorService {

  private final UserRepository userRepository;

  /**
   * 当前操作者
   *
   * @param userId 账号 id
   * @param role   账号角色（决定可执行的操作）
   */
  public record AdminOperator(Long userId, UserRole role) {}

  public AdminOperator current() {
    long userId = StpUserUtil.getLoginIdAsLong();
    UserEntity user = userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录"));
    if (!UserEntity.STATUS_ACTIVE.equals(user.getStatus())) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "该账号已被禁用，无法操作后台");
    }
    UserRole role = user.getRole() == null ? UserRole.USER : user.getRole();
    return new AdminOperator(user.getId(), role);
  }

  /**
   * 校验当前操作者具备指定权限，不足时抛业务异常（403）
   */
  public void requirePermission(Permission permission) {
    AdminOperator operator = current();
    if (!operator.role().hasPermission(permission)) {
      log.warn("后台操作被拒绝: userId={}, role={}, permission={}",
          operator.userId(), operator.role(), permission.getCode());
      throw new BusinessException(ErrorCode.USER_PERMISSION_DENIED,
          String.format("当前角色（%s）没有「%s」权限", operator.role().getLabel(), permission.getLabel()));
    }
  }
}

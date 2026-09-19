package interview.guide.modules.user.controller;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.user.model.AdminUserDTO;
import interview.guide.modules.user.model.AdminUserDTO.AdminUserResponse;
import interview.guide.modules.user.model.AdminUserDTO.CreateUserRequest;
import interview.guide.modules.user.model.AdminUserDTO.ResetPasswordRequest;
import interview.guide.modules.user.model.AdminUserDTO.RoleOption;
import interview.guide.modules.user.model.AdminUserDTO.UpdateUserRequest;
import interview.guide.modules.user.service.AdminUserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端用户管理：查看 / 新增 / 编辑 / 删除用户与分配角色权限
 *
 * <p>接口位于 /api/admin/**，由管理端登录态保护；具体操作所需权限由 AdminUserService 校验。</p>
 */
@Tag(name = "管理端用户管理", description = "后台用户列表、建号、编辑、删除与角色权限分配")
@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

  private final AdminUserService adminUserService;

  /**
   * 分页查询用户：keyword 命中昵称或登录账号
   */
  @GetMapping
  public Result<AdminUserDTO.UserPageResponse> page(
      @RequestParam(value = "page", defaultValue = "0") int page,
      @RequestParam(value = "size", defaultValue = "20") int size,
      @RequestParam(value = "keyword", required = false) String keyword,
      @RequestParam(value = "role", required = false) String role,
      @RequestParam(value = "status", required = false) String status) {
    return Result.success(adminUserService.page(keyword, role, status, page, size));
  }

  /**
   * 角色与权限目录（前端展示权限矩阵用）
   */
  @GetMapping("/roles")
  public Result<List<RoleOption>> roles() {
    return Result.success(adminUserService.roleCatalog());
  }

  @PostMapping
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 20)
  public Result<AdminUserResponse> create(@Valid @RequestBody CreateUserRequest request) {
    return Result.success(adminUserService.create(request));
  }

  @PutMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 30)
  public Result<AdminUserResponse> update(@PathVariable Long id,
                                          @Valid @RequestBody UpdateUserRequest request) {
    return Result.success(adminUserService.update(id, request));
  }

  @DeleteMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 20)
  public Result<Void> delete(@PathVariable Long id) {
    adminUserService.delete(id);
    return Result.success();
  }

  /**
   * 重置登录密码（重置后该账号需重新登录）
   */
  @PostMapping("/{id}/password")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 20)
  public Result<Void> resetPassword(@PathVariable Long id,
                                    @Valid @RequestBody ResetPasswordRequest request) {
    adminUserService.resetPassword(id, request.newPassword());
    return Result.success();
  }
}

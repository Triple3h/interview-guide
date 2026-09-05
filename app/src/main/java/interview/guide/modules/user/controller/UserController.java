package interview.guide.modules.user.controller;

import interview.guide.common.result.Result;
import interview.guide.common.web.CurrentUser;
import interview.guide.common.web.LoginUser;
import interview.guide.modules.user.model.UserDTO.CreateUserRequest;
import interview.guide.modules.user.model.UserDTO.UpdateUserRequest;
import interview.guide.modules.user.model.UserDTO.UserResponse;
import interview.guide.modules.user.service.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户（学习成员）管理
 * 选人、成员列表、资料编辑均无需登录态；列表/创建接口供首次进入时调用
 */
@Tag(name = "学习成员", description = "家庭成员选人与资料管理")
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 所有学习成员（首次进入选人用）
     */
    @GetMapping
    public Result<List<UserResponse>> list() {
        return Result.success(userService.list());
    }

    @GetMapping("/{id}")
    public Result<UserResponse> get(@PathVariable Long id) {
        return Result.success(userService.get(id));
    }

    @PostMapping
    public Result<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return Result.success(userService.create(request));
    }

    /**
     * 编辑当前登录人资料
     */
    @PutMapping("/{id}")
    public Result<UserResponse> update(@PathVariable Long id,
                                       @Valid @RequestBody UpdateUserRequest request,
                                       @LoginUser CurrentUser currentUser) {
        if (!currentUser.id().equals(id)) {
            return Result.error("只能修改自己的资料");
        }
        return Result.success(userService.update(id, request));
    }
}

package interview.guide.modules.auth.controller;

import interview.guide.common.result.Result;
import interview.guide.common.web.CurrentUser;
import interview.guide.common.web.LoginUser;
import interview.guide.modules.auth.StpUserUtil;
import interview.guide.modules.auth.model.AuthDTO;
import interview.guide.modules.auth.service.AuthService;
import interview.guide.modules.user.model.UserDTO;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学员鉴权：登录 / 登出 / 当前资料 / 修改密码
 */
@Tag(name = "鉴权", description = "学员登录、登出与密码管理")
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<AuthDTO.LoginResponse> login(@Valid @RequestBody AuthDTO.LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        StpUserUtil.logout();
        return Result.success();
    }

    @GetMapping("/me")
    public Result<UserDTO.UserResponse> me(@LoginUser CurrentUser currentUser) {
        return Result.success(authService.profile(currentUser.id()));
    }

    @PostMapping("/change-password")
    public Result<Void> changePassword(@LoginUser CurrentUser currentUser,
                                       @Valid @RequestBody AuthDTO.ChangePasswordRequest request) {
        authService.changePassword(currentUser.id(), request);
        return Result.success();
    }
}

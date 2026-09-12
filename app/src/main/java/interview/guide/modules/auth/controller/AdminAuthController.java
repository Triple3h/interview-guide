package interview.guide.modules.auth.controller;

import interview.guide.common.result.Result;
import interview.guide.modules.auth.model.AuthDTO;
import interview.guide.modules.auth.service.AuthService;
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
 * 管理端鉴权：固定 Token 登录（.env 的 APP_ADMIN_TOKEN）
 */
@Tag(name = "管理端鉴权", description = "管理端 Token 登录")
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AuthService authService;

    /**
     * 管理端登录：响应中的 token 供后续请求以请求头携带
     */
    @PostMapping("/login")
    public Result<AuthDTO.AdminLoginResponse> login(@Valid @RequestBody AuthDTO.AdminLoginRequest request) {
        return Result.success(authService.adminLogin(request));
    }

    /**
     * 登录态探针：登录页用它验证本地保存的 Token 是否仍然有效
     */
    @GetMapping("/ping")
    public Result<Void> ping() {
        return Result.success();
    }
}

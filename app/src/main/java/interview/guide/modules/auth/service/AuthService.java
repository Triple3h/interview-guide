package interview.guide.modules.auth.service;

import cn.dev33.satoken.stp.SaTokenInfo;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.UserMapper;
import interview.guide.modules.auth.StpAdminUtil;
import interview.guide.modules.auth.StpUserUtil;
import interview.guide.modules.auth.config.AuthProperties;
import interview.guide.modules.auth.model.AuthDTO;
import interview.guide.modules.user.model.UserDTO;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;

/**
 * 鉴权服务：学员账号密码登录 / 管理端固定 Token 登录 / 修改密码
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final AuthProperties authProperties;
    private final PasswordEncoder passwordEncoder;

    /**
     * 学员登录：用户名 + 密码（BCrypt 校验），成功后写入学员体系登录态
     */
    @Transactional
    public AuthDTO.LoginResponse login(AuthDTO.LoginRequest request) {
        String username = request.username().trim();
        UserEntity user = userRepository.findByUsername(username)
            .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));

        if (!UserEntity.STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该账号已被禁用，请联系管理员");
        }

        if (user.getPasswordHash() == null
            || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }

        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        StpUserUtil.login(user.getId());
        SaTokenInfo tokenInfo = StpUserUtil.getTokenInfo();
        log.info("学员登录成功: id={}, username={}", user.getId(), username);
        return new AuthDTO.LoginResponse(
            tokenInfo.getTokenValue(),
            tokenInfo.getTokenName(),
            new AuthDTO.UserProfile(user.getId(), user.getUsername(), user.getNickname(), user.getAvatarEmoji()));
    }

    /**
     * 管理端登录：校验收到的 Token 与 .env 配置的 APP_ADMIN_TOKEN 是否一致（常量时间比较）
     */
    public AuthDTO.AdminLoginResponse adminLogin(AuthDTO.AdminLoginRequest request) {
        String expected = authProperties.getAdminToken();
        if (expected == null || expected.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED,
                "管理端 Token 尚未配置，请在 .env 中设置 APP_ADMIN_TOKEN");
        }

        String given = request.token() == null ? "" : request.token().trim();
        boolean matched = MessageDigest.isEqual(
            given.getBytes(StandardCharsets.UTF_8),
            expected.trim().getBytes(StandardCharsets.UTF_8));
        if (!matched) {
            log.warn("管理端 Token 登录失败");
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "管理端 Token 无效");
        }

        StpAdminUtil.login(StpAdminUtil.ADMIN_LOGIN_ID);
        SaTokenInfo tokenInfo = StpAdminUtil.getTokenInfo();
        log.info("管理端登录成功");
        return new AuthDTO.AdminLoginResponse(tokenInfo.getTokenValue(), tokenInfo.getTokenName());
    }

    /**
     * 当前登录学员资料（/api/auth/me）
     */
    public UserDTO.UserResponse profile(Long userId) {
        return userMapper.toResponse(getUser(userId));
    }

    /**
     * 修改密码：校验原密码后写入新哈希
     */
    @Transactional
    public void changePassword(Long userId, AuthDTO.ChangePasswordRequest request) {
        UserEntity user = getUser(userId);
        if (user.getPasswordHash() != null
            && !passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "原密码不正确");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        log.info("学员修改密码: id={}", userId);
    }

    private UserEntity getUser(Long userId) {
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}

package interview.guide.modules.auth.service;

import cn.dev33.satoken.stp.SaTokenInfo;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.UserMapper;
import interview.guide.modules.auth.StpUserUtil;
import interview.guide.modules.auth.model.AuthDTO;
import interview.guide.modules.user.model.UserDTO;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.model.UserRole;
import interview.guide.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 鉴权服务：账号密码登录 / 修改密码
 *
 * <p>全站只有一套登录态（学员体系）：管理员与超级管理员登录同一个入口，
 * 后台能力由角色决定（见 AdminOperatorService）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 账号登录：用户名 + 密码（BCrypt 校验），成功后写入登录态
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
        UserRole role = user.getRole() == null ? UserRole.USER : user.getRole();
        log.info("账号登录成功: id={}, username={}, role={}", user.getId(), username, role);
        return new AuthDTO.LoginResponse(
            tokenInfo.getTokenValue(),
            tokenInfo.getTokenName(),
            new AuthDTO.UserProfile(user.getId(), user.getUsername(), user.getNickname(),
                user.getAvatarEmoji(), role.name(), role.getLabel()));
    }

    /**
     * 当前登录账号资料（/api/auth/me）
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

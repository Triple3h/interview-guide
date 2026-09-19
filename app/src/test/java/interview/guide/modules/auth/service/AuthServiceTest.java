package interview.guide.modules.auth.service;

import cn.dev33.satoken.stp.SaTokenInfo;
import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.mapper.UserMapper;
import interview.guide.modules.auth.StpUserUtil;
import interview.guide.modules.auth.model.AuthDTO;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.model.UserRole;
import interview.guide.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("鉴权服务测试（账号登录 / 改密）")
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService authService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        authService = new AuthService(userRepository, userMapper, passwordEncoder);
    }

    private UserEntity activeUser() {
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUsername("alice");
        user.setNickname("Alice");
        user.setStatus(UserEntity.STATUS_ACTIVE);
        user.setPasswordHash(passwordEncoder.encode("secret123"));
        return user;
    }

    @Nested
    @DisplayName("学员登录")
    class UserLogin {

        @Test
        @DisplayName("用户名密码正确时返回 token 与资料，并刷新最近登录时间")
        void shouldLoginSuccessfully() {
            UserEntity user = activeUser();
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

            SaTokenInfo tokenInfo = mock(SaTokenInfo.class);
            when(tokenInfo.getTokenValue()).thenReturn("user-token-value");
            when(tokenInfo.getTokenName()).thenReturn("sa-token");

            try (MockedStatic<StpUserUtil> mocked = Mockito.mockStatic(StpUserUtil.class)) {
                mocked.when(StpUserUtil::getTokenInfo).thenReturn(tokenInfo);

                AuthDTO.LoginResponse response =
                    authService.login(new AuthDTO.LoginRequest("alice", "secret123"));

                assertThat(response.token()).isEqualTo("user-token-value");
                assertThat(response.profile().id()).isEqualTo(1L);
                assertThat(response.profile().username()).isEqualTo("alice");
                assertThat(user.getLastLoginAt()).isNotNull();
                mocked.verify(() -> StpUserUtil.login(1L));
            }
        }

        @Test
        @DisplayName("用户名不存在时抛出「用户名或密码错误」")
        void shouldRejectUnknownUsername() {
            when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(new AuthDTO.LoginRequest("nobody", "x")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名或密码错误");
        }

        @Test
        @DisplayName("密码错误时抛出「用户名或密码错误」")
        void shouldRejectWrongPassword() {
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(activeUser()));

            assertThatThrownBy(() -> authService.login(new AuthDTO.LoginRequest("alice", "wrong")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名或密码错误");
        }

        @Test
        @DisplayName("账号被禁用时拒绝登录")
        void shouldRejectDisabledUser() {
            UserEntity user = activeUser();
            user.setStatus(UserEntity.STATUS_DISABLED);
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> authService.login(new AuthDTO.LoginRequest("alice", "secret123")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已被禁用");

            verify(userRepository, never()).save(any(UserEntity.class));
        }

        @Test
        @DisplayName("存量成员无密码时拒绝登录")
        void shouldRejectUserWithoutPassword() {
            UserEntity user = activeUser();
            user.setPasswordHash(null);
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> authService.login(new AuthDTO.LoginRequest("alice", "secret123")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名或密码错误");
        }
    }

    @Nested
    @DisplayName("登录响应携带角色")
    class LoginRole {

        @Test
        @DisplayName("超级管理员登录后返回角色编码与中文名")
        void shouldReturnRoleInProfile() {
            UserEntity user = activeUser();
            user.setRole(UserRole.SUPER_ADMIN);
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

            SaTokenInfo tokenInfo = mock(SaTokenInfo.class);
            when(tokenInfo.getTokenValue()).thenReturn("user-token-value");
            when(tokenInfo.getTokenName()).thenReturn("sa-token");

            try (MockedStatic<StpUserUtil> mocked = Mockito.mockStatic(StpUserUtil.class)) {
                mocked.when(StpUserUtil::getTokenInfo).thenReturn(tokenInfo);

                AuthDTO.LoginResponse response =
                    authService.login(new AuthDTO.LoginRequest("alice", "secret123"));

                assertThat(response.profile().role()).isEqualTo("SUPER_ADMIN");
                assertThat(response.profile().roleLabel()).isEqualTo("超级管理员");
            }
        }
    }

    @Nested
    @DisplayName("修改密码")
    class ChangePassword {

        @Test
        @DisplayName("原密码错误时拒绝修改")
        void shouldRejectWrongOldPassword() {
            UserEntity user = activeUser();
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> authService.changePassword(1L,
                new AuthDTO.ChangePasswordRequest("wrong", "newpass123")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("原密码不正确");

            verify(userRepository, never()).save(any(UserEntity.class));
        }

        @Test
        @DisplayName("原密码正确时写入新密码哈希")
        void shouldUpdatePasswordHash() {
            UserEntity user = activeUser();
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            authService.changePassword(1L, new AuthDTO.ChangePasswordRequest("secret123", "newpass123"));

            assertThat(passwordEncoder.matches("newpass123", user.getPasswordHash())).isTrue();
            verify(userRepository).save(user);
        }
    }
}

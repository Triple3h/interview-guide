package interview.guide.modules.user.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.UserMapper;
import interview.guide.modules.auth.service.AdminOperatorService;
import interview.guide.modules.user.model.AdminUserDTO.CreateUserRequest;
import interview.guide.modules.user.model.AdminUserDTO.RoleOption;
import interview.guide.modules.user.model.AdminUserDTO.UpdateUserRequest;
import interview.guide.modules.user.model.Permission;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.model.UserRole;
import interview.guide.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("管理端用户服务测试（角色与权限）")
class AdminUserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AdminOperatorService adminOperatorService;

    private AdminUserService adminUserService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        adminUserService = new AdminUserService(userRepository, userMapper, passwordEncoder, adminOperatorService);
    }

    private UserEntity user(Long id, String nickname, String username, UserRole role, String status) {
        UserEntity entity = new UserEntity();
        entity.setId(id);
        entity.setNickname(nickname);
        entity.setUsername(username);
        entity.setRole(role);
        entity.setStatus(status);
        return entity;
    }

    private CreateUserRequest createRequest(String nickname, String username, String role) {
        return new CreateUserRequest(nickname, username, "secret123", role, null, null, null, null, null, null);
    }

    @Nested
    @DisplayName("新建用户")
    class Create {

        @Test
        @DisplayName("昵称重复时抛出 USER_NICKNAME_DUPLICATED")
        void shouldThrowWhenNicknameDuplicated() {
            when(userRepository.findByNickname("小辉")).thenReturn(Optional.of(user(1L, "小辉", "a", UserRole.USER, "ACTIVE")));

            assertThatThrownBy(() -> adminUserService.create(createRequest("小辉", "tripleh", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("昵称已被使用");
            verify(userRepository, never()).save(any(UserEntity.class));
        }

        @Test
        @DisplayName("登录账号重复时抛出 USER_USERNAME_DUPLICATED")
        void shouldThrowWhenUsernameDuplicated() {
            when(userRepository.findByNickname("小辉")).thenReturn(Optional.empty());
            when(userRepository.findByUsername("tripleh")).thenReturn(Optional.of(user(1L, "别人", "tripleh", UserRole.USER, "ACTIVE")));

            assertThatThrownBy(() -> adminUserService.create(createRequest("小辉", "tripleh", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("登录账号已被使用");
        }

        @Test
        @DisplayName("未指定角色时按普通用户创建，并加密写入初始密码")
        void shouldCreateDefaultUserWithEncodedPassword() {
            when(userRepository.findByNickname("小辉")).thenReturn(Optional.empty());
            when(userRepository.findByUsername("tripleh")).thenReturn(Optional.empty());
            when(passwordEncoder.encode("secret123")).thenReturn("hashed");
            when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            adminUserService.create(createRequest("小辉", "tripleh", null));

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getRole()).isEqualTo(UserRole.USER);
            assertThat(captor.getValue().getStatus()).isEqualTo(UserEntity.STATUS_ACTIVE);
            assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed");
        }

        @Test
        @DisplayName("授予管理员及以上角色需要超级管理员权限")
        void shouldRequireRolePermissionWhenGrantingAdmin() {
            doThrow(new BusinessException(ErrorCode.USER_PERMISSION_DENIED, "没有权限执行该操作"))
                .when(adminOperatorService).requirePermission(Permission.USER_ROLE);
            when(userRepository.findByNickname("小辉")).thenReturn(Optional.empty());
            when(userRepository.findByUsername("tripleh")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adminUserService.create(createRequest("小辉", "tripleh", "ADMIN")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有权限");
            verify(userRepository, never()).save(any(UserEntity.class));
        }
    }

    @Nested
    @DisplayName("编辑用户")
    class Update {

        @Test
        @DisplayName("把最后一个超级管理员降级时抛出 USER_LAST_SUPER_ADMIN")
        void shouldThrowWhenDemotingLastSuperAdmin() {
            UserEntity superAdmin = user(1L, "小辉", "tripleh", UserRole.SUPER_ADMIN, UserEntity.STATUS_ACTIVE);
            when(userRepository.findById(1L)).thenReturn(Optional.of(superAdmin));
            when(userRepository.countByRole(UserRole.SUPER_ADMIN)).thenReturn(1L);

            UpdateUserRequest request = new UpdateUserRequest(null, null, "ADMIN", null, null, null, null, null, null);
            assertThatThrownBy(() -> adminUserService.update(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少保留一个超级管理员");
        }

        @Test
        @DisplayName("禁用最后一个超级管理员时同样被拒绝")
        void shouldThrowWhenDisablingLastSuperAdmin() {
            UserEntity superAdmin = user(1L, "小辉", "tripleh", UserRole.SUPER_ADMIN, UserEntity.STATUS_ACTIVE);
            when(userRepository.findById(1L)).thenReturn(Optional.of(superAdmin));
            when(userRepository.countByRole(UserRole.SUPER_ADMIN)).thenReturn(1L);

            UpdateUserRequest request = new UpdateUserRequest(null, null, null, "DISABLED", null, null, null, null, null);
            assertThatThrownBy(() -> adminUserService.update(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少保留一个超级管理员");
        }

        @Test
        @DisplayName("存在多个超级管理员时可以降级其中一个")
        void shouldAllowDemotingWhenAnotherSuperAdminExists() {
            UserEntity superAdmin = user(1L, "小辉", "tripleh", UserRole.SUPER_ADMIN, UserEntity.STATUS_ACTIVE);
            when(userRepository.findById(1L)).thenReturn(Optional.of(superAdmin));
            when(userRepository.countByRole(UserRole.SUPER_ADMIN)).thenReturn(2L);
            when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            UpdateUserRequest request = new UpdateUserRequest(null, null, "ADMIN", null, null, null, null, null, null);
            adminUserService.update(1L, request);

            assertThat(superAdmin.getRole()).isEqualTo(UserRole.ADMIN);
        }

        @Test
        @DisplayName("非法状态值抛出 BAD_REQUEST")
        void shouldThrowOnInvalidStatus() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "小辉", "tripleh", UserRole.USER, "ACTIVE")));

            UpdateUserRequest request = new UpdateUserRequest(null, null, null, "LOCKED", null, null, null, null, null);
            assertThatThrownBy(() -> adminUserService.update(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无效的账号状态");
        }
    }

    @Nested
    @DisplayName("删除与重置密码")
    class DeleteAndPassword {

        @Test
        @DisplayName("最后一个超级管理员不可删除")
        void shouldRejectDeletingLastSuperAdmin() {
            when(userRepository.findById(1L))
                .thenReturn(Optional.of(user(1L, "小辉", "tripleh", UserRole.SUPER_ADMIN, UserEntity.STATUS_ACTIVE)));
            when(userRepository.countByRole(UserRole.SUPER_ADMIN)).thenReturn(1L);

            assertThatThrownBy(() -> adminUserService.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少保留一个超级管理员");
            verify(userRepository, never()).delete(any(UserEntity.class));
        }

        @Test
        @DisplayName("普通用户可删除")
        void shouldDeleteNormalUser() {
            UserEntity target = user(2L, "小明", "ming", UserRole.USER, UserEntity.STATUS_ACTIVE);
            when(userRepository.findById(2L)).thenReturn(Optional.of(target));

            adminUserService.delete(2L);

            verify(userRepository).delete(target);
        }

        @Test
        @DisplayName("重置密码写入新哈希")
        void shouldEncodeNewPassword() {
            UserEntity target = user(2L, "小明", "ming", UserRole.USER, UserEntity.STATUS_ACTIVE);
            when(userRepository.findById(2L)).thenReturn(Optional.of(target));
            when(passwordEncoder.encode("newpass123")).thenReturn("new-hash");

            adminUserService.resetPassword(2L, "newpass123");

            assertThat(target.getPasswordHash()).isEqualTo("new-hash");
            verify(userRepository).save(target);
        }

        @Test
        @DisplayName("重置密码过短时抛出 BAD_REQUEST")
        void shouldRejectShortPassword() {
            when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "小明", "ming", UserRole.USER, "ACTIVE")));

            assertThatThrownBy(() -> adminUserService.resetPassword(2L, "123"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("6-64");
        }
    }

    @Nested
    @DisplayName("分页与角色目录")
    class Query {

        @Test
        @DisplayName("每页条数上限 100，页码非负")
        void shouldClampPageRequest() {
            when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

            adminUserService.page("小", "ADMIN", "ACTIVE", -5, 1000);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(userRepository).findAll(any(Specification.class), captor.capture());
            assertThat(captor.getValue().getPageNumber()).isZero();
            assertThat(captor.getValue().getPageSize()).isEqualTo(100);
        }

        @Test
        @DisplayName("角色目录返回每个角色拥有的权限")
        void shouldReturnRoleCatalog() {
            List<RoleOption> catalog = adminUserService.roleCatalog();

            assertThat(catalog).extracting(RoleOption::code)
                .containsExactly("SUPER_ADMIN", "ADMIN", "USER");
            RoleOption superAdmin = catalog.get(0);
            assertThat(superAdmin.permissions()).extracting(item -> item.code())
                .contains(Permission.USER_ROLE.getCode(), Permission.USER_VIEW.getCode());
            RoleOption normal = catalog.get(2);
            assertThat(normal.permissions()).extracting(item -> item.code())
                .doesNotContain(Permission.USER_VIEW.getCode());
        }
    }

    @Nested
    @DisplayName("权限校验")
    class PermissionGuard {

        @Test
        @DisplayName("操作者无权限时删除被拒绝")
        void shouldRejectDeleteWithoutPermission() {
            doThrow(new BusinessException(ErrorCode.USER_PERMISSION_DENIED, "当前角色（管理员）没有「分配角色与权限」权限"))
                .when(adminOperatorService).requirePermission(Permission.USER_ROLE);

            assertThatThrownBy(() -> adminUserService.delete(2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有「分配角色与权限」权限");
            verify(userRepository, never()).findById(anyLong());
        }
    }
}

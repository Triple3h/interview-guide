package interview.guide.modules.user.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.mapper.UserMapper;
import interview.guide.modules.user.model.UserCreatedEvent;
import interview.guide.modules.user.model.UserDTO.CreateUserRequest;
import interview.guide.modules.user.model.UserDTO.UpdateUserRequest;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("用户服务测试（极简选人模式）")
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private UserService userService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        userService = new UserService(userRepository, userMapper, eventPublisher);
    }

    @Nested
    @DisplayName("创建成员")
    class Create {

        @Test
        @DisplayName("昵称重复时抛出 USER_NICKNAME_DUPLICATED")
        void shouldThrowWhenNicknameDuplicated() {
            when(userRepository.existsByNickname("Alice")).thenReturn(true);

            assertThatThrownBy(() -> userService.create(new CreateUserRequest("Alice", null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("昵称已被使用");
        }

        @Test
        @DisplayName("首位成员创建后发布 UserCreatedEvent")
        void shouldPublishEventWhenFirstUserCreated() {
            when(userRepository.existsByNickname("Alice")).thenReturn(false);
            when(userRepository.count()).thenReturn(0L);
            when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> {
                UserEntity entity = invocation.getArgument(0);
                entity.setId(42L);
                return entity;
            });

            userService.create(new CreateUserRequest("Alice", "🦊", "工程师", "Java", "入门", "系统学习"));

            ArgumentCaptor<UserCreatedEvent> captor = ArgumentCaptor.forClass(UserCreatedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().userId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("非首位成员不发布事件")
        void shouldNotPublishEventWhenUsersExist() {
            when(userRepository.existsByNickname("Bob")).thenReturn(false);
            when(userRepository.count()).thenReturn(1L);
            when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            userService.create(new CreateUserRequest("Bob", null, null, null, null, null));

            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("昵称两端空格会被去除")
        void shouldTrimNickname() {
            when(userRepository.existsByNickname("Alice")).thenReturn(false);
            when(userRepository.count()).thenReturn(1L);
            when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            userService.create(new CreateUserRequest("  Alice  ", null, null, null, null, null));

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getNickname()).isEqualTo("Alice");
        }
    }

    @Nested
    @DisplayName("更新资料")
    class Update {

        @Test
        @DisplayName("把昵称改成他人昵称时抛出 USER_NICKNAME_DUPLICATED")
        void shouldThrowWhenNicknameTakenByOther() {
            UserEntity self = new UserEntity();
            self.setId(1L);
            UserEntity other = new UserEntity();
            other.setId(2L);
            other.setNickname("Bob");

            when(userRepository.findById(1L)).thenReturn(Optional.of(self));
            when(userRepository.findByNickname("Bob")).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> userService.update(1L, new UpdateUserRequest("Bob", null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("昵称已被使用");
        }

        @Test
        @DisplayName("保留自己的昵称可以更新其他字段")
        void shouldAllowKeepingOwnNickname() {
            UserEntity self = new UserEntity();
            self.setId(1L);
            self.setNickname("Alice");

            when(userRepository.findById(1L)).thenReturn(Optional.of(self));
            when(userRepository.findByNickname("Alice")).thenReturn(Optional.of(self));
            when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            userService.update(1L, new UpdateUserRequest("Alice", "🚀", null, null, null, null));

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getAvatarEmoji()).isEqualTo("🚀");
        }
    }
}

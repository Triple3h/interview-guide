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

            assertThatThrownBy(() -> userService.create(new CreateUserRequest("Alice", null, null, null, null, null, null)))
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

            userService.create(new CreateUserRequest("Alice", "🦊", "工程师", "Java", "java-backend", "入门", "系统学习"));

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

            userService.create(new CreateUserRequest("Bob", null, null, null, null, null, null));

            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("昵称两端空格会被去除")
        void shouldTrimNickname() {
            when(userRepository.existsByNickname("Alice")).thenReturn(false);
            when(userRepository.count()).thenReturn(1L);
            when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            userService.create(new CreateUserRequest("  Alice  ", null, null, null, null, null, null));

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

            assertThatThrownBy(() -> userService.update(1L, new UpdateUserRequest("Bob", null, null, null, null, null, null)))
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

            userService.update(1L, new UpdateUserRequest("Alice", "🚀", null, null, null, null, null));

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getAvatarEmoji()).isEqualTo("🚀");
        }

        @Test
        @DisplayName("更新学习方向时保存关联的预置 skill id")
        void shouldSaveLearningSkillId() {
            UserEntity self = new UserEntity();
            self.setId(1L);
            self.setNickname("Alice");

            when(userRepository.findById(1L)).thenReturn(Optional.of(self));
            when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            userService.update(1L, new UpdateUserRequest(null, null, null, "Java 后端开发", "java-backend", null, null));

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getLearningDirection()).isEqualTo("Java 后端开发");
            assertThat(captor.getValue().getLearningSkillId()).isEqualTo("java-backend");
        }

        @Test
        @DisplayName("自定义学习方向传空串时清除关联的 skill id")
        void shouldClearLearningSkillIdWithBlank() {
            UserEntity self = new UserEntity();
            self.setId(1L);
            self.setNickname("Alice");
            self.setLearningSkillId("java-backend");

            when(userRepository.findById(1L)).thenReturn(Optional.of(self));
            when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            userService.update(1L, new UpdateUserRequest(null, null, null, "英语口语", "", null, null));

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getLearningSkillId()).isNull();
        }
    }

    @Nested
    @DisplayName("Agent 补充资料")
    class AgentUpdateProfile {

        @Test
        @DisplayName("只写入本次提供的字段，未提供的字段保持原值")
        void shouldOnlyApplyProvidedFields() {
            UserEntity self = new UserEntity();
            self.setId(1L);
            self.setNickname("Alice");
            self.setOccupation("教师");
            self.setLearningGoal("原目标");

            when(userRepository.findById(1L)).thenReturn(Optional.of(self));
            when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            UserService.ProfileUpdateResult result = userService.updateProfileFromAgent(
                1L, null, "Java 后端开发", "java-backend", "能用但不系统", null);

            assertThat(result.changedFields()).containsExactly("学习方向", "当前水平");
            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getOccupation()).isEqualTo("教师");
            assertThat(captor.getValue().getLearningGoal()).isEqualTo("原目标");
            assertThat(captor.getValue().getLearningDirection()).isEqualTo("Java 后端开发");
            assertThat(captor.getValue().getLearningSkillId()).isEqualTo("java-backend");
            assertThat(captor.getValue().getCurrentLevel()).isEqualTo("能用但不系统");
        }

        @Test
        @DisplayName("自定义方向随空串清除旧的预置方向标识")
        void shouldClearSkillIdForCustomDirection() {
            UserEntity self = new UserEntity();
            self.setId(1L);
            self.setNickname("Alice");
            self.setLearningDirection("Java 后端开发");
            self.setLearningSkillId("java-backend");

            when(userRepository.findById(1L)).thenReturn(Optional.of(self));
            when(userRepository.save(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

            UserService.ProfileUpdateResult result = userService.updateProfileFromAgent(
                1L, null, "英语口语", "", null, null);

            assertThat(result.changedFields()).containsExactly("学习方向");
            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).save(captor.capture());
            assertThat(captor.getValue().getLearningDirection()).isEqualTo("英语口语");
            assertThat(captor.getValue().getLearningSkillId()).isNull();
        }

        @Test
        @DisplayName("全部未提供或为空白时不落库、不清空既有资料")
        void shouldSkipWhenNothingProvided() {
            UserEntity self = new UserEntity();
            self.setId(1L);
            self.setNickname("Alice");
            self.setOccupation("教师");

            when(userRepository.findById(1L)).thenReturn(Optional.of(self));

            UserService.ProfileUpdateResult result = userService.updateProfileFromAgent(
                1L, "  ", "", null, null, null);

            assertThat(result.changedFields()).isEmpty();
            assertThat(self.getOccupation()).isEqualTo("教师");
            verify(userRepository, never()).save(any(UserEntity.class));
        }
    }
}

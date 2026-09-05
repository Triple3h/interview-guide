package interview.guide.modules.user.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.UserMapper;
import interview.guide.modules.user.model.UserCreatedEvent;
import interview.guide.modules.user.model.UserDTO.CreateUserRequest;
import interview.guide.modules.user.model.UserDTO.UpdateUserRequest;
import interview.guide.modules.user.model.UserDTO.UserResponse;
import interview.guide.modules.user.model.UserEntity;
import interview.guide.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户服务（极简选人模式）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 所有学习成员（选人列表按创建时间排序，保持稳定）
     */
    public List<UserResponse> list() {
        return userMapper.toResponseList(userRepository.findAllByOrderByCreatedAtAsc());
    }

    public UserResponse get(Long id) {
        return userMapper.toResponse(getEntity(id));
    }

    /**
     * 创建成员。第一位成员创建后发布事件，由会话模块把无归属的存量会话划归该成员。
     */
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String nickname = request.nickname().trim();
        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(ErrorCode.USER_NICKNAME_DUPLICATED);
        }

        boolean firstUser = userRepository.count() == 0;

        UserEntity entity = new UserEntity();
        entity.setNickname(nickname);
        applyProfile(entity, request.avatarEmoji(), request.occupation(),
            request.learningDirection(), request.currentLevel(), request.learningGoal());
        entity = userRepository.save(entity);

        if (firstUser) {
            eventPublisher.publishEvent(new UserCreatedEvent(entity.getId()));
        }

        log.info("创建学习成员: id={}, nickname={}, firstUser={}", entity.getId(), nickname, firstUser);
        return userMapper.toResponse(entity);
    }

    /**
     * 更新资料（null 字段不修改）
     */
    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request) {
        UserEntity entity = getEntity(id);

        if (request.nickname() != null) {
            String nickname = request.nickname().trim();
            if (nickname.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "昵称不能为空");
            }
            userRepository.findByNickname(nickname)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new BusinessException(ErrorCode.USER_NICKNAME_DUPLICATED);
                });
            entity.setNickname(nickname);
        }

        applyProfile(entity, request.avatarEmoji(), request.occupation(),
            request.learningDirection(), request.currentLevel(), request.learningGoal());

        log.info("更新学习成员资料: id={}", id);
        return userMapper.toResponse(userRepository.save(entity));
    }

    public UserEntity getEntity(Long id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private void applyProfile(UserEntity entity, String avatarEmoji, String occupation,
                              String learningDirection, String currentLevel, String learningGoal) {
        if (avatarEmoji != null) {
            entity.setAvatarEmoji(normalize(avatarEmoji, 8));
        }
        if (occupation != null) {
            entity.setOccupation(normalize(occupation, 100));
        }
        if (learningDirection != null) {
            entity.setLearningDirection(normalize(learningDirection, 100));
        }
        if (currentLevel != null) {
            entity.setCurrentLevel(normalize(currentLevel, 200));
        }
        if (learningGoal != null) {
            entity.setLearningGoal(normalize(learningGoal, 500));
        }
    }

    private String normalize(String value, int maxLength) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : (trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed);
    }
}

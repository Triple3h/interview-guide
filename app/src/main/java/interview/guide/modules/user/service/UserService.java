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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
            request.learningDirection(), request.learningSkillId(),
            request.currentLevel(), request.learningGoal());
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
            request.learningDirection(), request.learningSkillId(),
            request.currentLevel(), request.learningGoal());

        log.info("更新学习成员资料: id={}", id);
        return userMapper.toResponse(userRepository.save(entity));
    }

    public UserEntity getEntity(Long id) {
        return userRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    /**
     * Agent 学习帮手补充学员资料：只写入本次明确提供的字段，未提供或空白的字段保持原值（不会清空既有资料）。
     * learningSkillId 由调用方随 learningDirection 一并给出：命中预置方向传 skill id，自定义方向传空串以清除旧关联。
     *
     * @return 更新后的资料，以及本次实际发生变化的字段中文名（用于向学员复述）
     */
    @Transactional
    public ProfileUpdateResult updateProfileFromAgent(Long id, String occupation, String learningDirection,
                                                      String learningSkillId, String currentLevel, String learningGoal) {
        UserEntity entity = getEntity(id);

        String oldOccupation = entity.getOccupation();
        String oldDirection = entity.getLearningDirection();
        String oldSkillId = entity.getLearningSkillId();
        String oldLevel = entity.getCurrentLevel();
        String oldGoal = entity.getLearningGoal();

        applyProfile(entity, null, textOrNull(occupation), textOrNull(learningDirection),
            learningSkillId, textOrNull(currentLevel), textOrNull(learningGoal));

        List<String> changedFields = new ArrayList<>();
        if (!Objects.equals(oldOccupation, entity.getOccupation())) {
            changedFields.add("职业");
        }
        if (!Objects.equals(oldDirection, entity.getLearningDirection())
            || !Objects.equals(oldSkillId, entity.getLearningSkillId())) {
            changedFields.add("学习方向");
        }
        if (!Objects.equals(oldLevel, entity.getCurrentLevel())) {
            changedFields.add("当前水平");
        }
        if (!Objects.equals(oldGoal, entity.getLearningGoal())) {
            changedFields.add("学习目标");
        }

        if (changedFields.isEmpty()) {
            log.info("Agent 提交学员资料补充但无有效变更: id={}", id);
            return new ProfileUpdateResult(userMapper.toResponse(entity), List.of());
        }

        UserResponse response = userMapper.toResponse(userRepository.save(entity));
        log.info("Agent 更新学习成员资料: id={}, changedFields={}", id, changedFields);
        return new ProfileUpdateResult(response, List.copyOf(changedFields));
    }

    private String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void applyProfile(UserEntity entity, String avatarEmoji, String occupation,
                              String learningDirection, String learningSkillId,
                              String currentLevel, String learningGoal) {
        if (avatarEmoji != null) {
            entity.setAvatarEmoji(normalize(avatarEmoji, 8));
        }
        if (occupation != null) {
            entity.setOccupation(normalize(occupation, 100));
        }
        if (learningDirection != null) {
            entity.setLearningDirection(normalize(learningDirection, 100));
        }
        if (learningSkillId != null) {
            // 前端始终随资料整体提交：选了预置方向传 skillId，自定义方向传空串以清除
            entity.setLearningSkillId(normalize(learningSkillId, 50));
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

    /**
     * Agent 更新资料结果：更新后的资料 + 本次实际变更的字段中文名
     */
    public record ProfileUpdateResult(UserResponse profile, List<String> changedFields) {}
}

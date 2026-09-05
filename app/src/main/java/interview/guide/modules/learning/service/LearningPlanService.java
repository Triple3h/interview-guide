package interview.guide.modules.learning.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.LearningPlanItemMapper;
import interview.guide.modules.learning.model.LearningPlanDTO.CreatePlanItemRequest;
import interview.guide.modules.learning.model.LearningPlanDTO.PlanItemResponse;
import interview.guide.modules.learning.model.LearningPlanDTO.UpdatePlanItemRequest;
import interview.guide.modules.learning.model.LearningPlanItemEntity;
import interview.guide.modules.learning.model.LearningPlanItemEntity.Status;
import interview.guide.modules.learning.repository.LearningPlanItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 学习计划服务
 * Agent 与学员商定后固化与手动维护共用同一套读写逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningPlanService {

    private static final int TOPIC_MAX_LENGTH = 200;
    private static final int GOAL_MAX_LENGTH = 500;
    private static final int MAX_PLAN_ITEMS = 20;

    private final LearningPlanItemRepository planRepository;
    private final LearningPlanItemMapper planMapper;

    /**
     * Agent 工具入参：一条计划
     */
    public record AgentPlanItem(String topic, String goal, String status) {}

    /**
     * 我的计划（按 sortOrder 排列）
     */
    public List<PlanItemResponse> list(Long userId) {
        return planMapper.toResponseList(listEntities(userId));
    }

    /**
     * 计划实体列表（Agent 工具与 system prompt 注入用）
     */
    public List<LearningPlanItemEntity> listEntities(Long userId) {
        return planRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
    }

    /**
     * Agent 固化计划条目：同名主题（忽略大小写）更新，否则新增；列表顺序即计划顺序
     */
    @Transactional
    public UpsertResult upsertFromAgent(Long userId, List<AgentPlanItem> items, Long sourceSessionId) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "计划条目不能为空");
        }
        if (items.size() > MAX_PLAN_ITEMS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "计划条目最多 " + MAX_PLAN_ITEMS + " 条，请先和学员收敛重点");
        }

        int created = 0;
        int updated = 0;
        for (int i = 0; i < items.size(); i++) {
            AgentPlanItem item = items.get(i);
            String cleanTopic = normalizeTopic(item.topic());
            String cleanGoal = normalizeGoal(item.goal());
            Status status = parseStatusLenient(item.status());

            LearningPlanItemEntity entity = planRepository
                .findByUserIdAndTopicIgnoreCase(userId, cleanTopic)
                .orElse(null);
            if (entity == null) {
                entity = new LearningPlanItemEntity();
                entity.setUserId(userId);
                entity.setTopic(cleanTopic);
                created++;
            } else {
                updated++;
            }
            entity.setGoal(cleanGoal);
            entity.setStatus(status);
            entity.setSortOrder(i);
            entity.setSourceSessionId(sourceSessionId);
            planRepository.save(entity);
        }

        log.info("Agent 固化学习计划: userId={}, items={}, created={}, updated={}",
            userId, items.size(), created, updated);
        return new UpsertResult(items.size(), created, updated);
    }

    /**
     * upsert 结果：total 为本次提交条数，created/updated 为新建/更新条数
     */
    public record UpsertResult(int total, int created, int updated) {
    }

    /**
     * 手动新增（排在现有条目之后）
     */
    @Transactional
    public PlanItemResponse create(Long userId, CreatePlanItemRequest request) {
        String cleanTopic = normalizeTopic(request.topic());
        planRepository.findByUserIdAndTopicIgnoreCase(userId, cleanTopic)
            .ifPresent(existing -> {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "计划条目「" + cleanTopic + "」已存在");
            });

        int nextOrder = planRepository.findFirstByUserIdOrderBySortOrderDesc(userId)
            .map(e -> e.getSortOrder() + 1)
            .orElse(0);

        LearningPlanItemEntity entity = new LearningPlanItemEntity();
        entity.setUserId(userId);
        entity.setTopic(cleanTopic);
        entity.setGoal(normalizeGoal(request.goal()));
        entity.setSortOrder(nextOrder);
        entity = planRepository.save(entity);

        log.info("手动新增学习计划条目: userId={}, topic={}", userId, cleanTopic);
        return planMapper.toResponse(entity);
    }

    /**
     * 手动更新（null 字段不修改）
     */
    @Transactional
    public PlanItemResponse update(Long userId, Long id, UpdatePlanItemRequest request) {
        LearningPlanItemEntity entity = getOwnedItem(userId, id);

        if (request.topic() != null && !request.topic().isBlank()) {
            entity.setTopic(normalizeTopic(request.topic()));
        }
        if (request.goal() != null) {
            entity.setGoal(normalizeGoal(request.goal()));
        }
        if (request.status() != null) {
            entity.setStatus(parseStatusStrict(request.status()));
        }
        return planMapper.toResponse(planRepository.save(entity));
    }

    /**
     * 仅更新状态（前端点选）
     */
    @Transactional
    public PlanItemResponse updateStatus(Long userId, Long id, String status) {
        LearningPlanItemEntity entity = getOwnedItem(userId, id);
        entity.setStatus(parseStatusStrict(status));
        return planMapper.toResponse(planRepository.save(entity));
    }

    /**
     * 删除（仅本人的条目）
     */
    @Transactional
    public void delete(Long userId, Long id) {
        getOwnedItem(userId, id);
        planRepository.deleteById(id);
        log.info("删除学习计划条目: userId={}, id={}", userId, id);
    }

    private LearningPlanItemEntity getOwnedItem(Long userId, Long id) {
        LearningPlanItemEntity entity = planRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.LEARNING_PLAN_ITEM_NOT_FOUND));
        if (!entity.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.LEARNING_PLAN_ITEM_NOT_FOUND);
        }
        return entity;
    }

    /**
     * Agent 入参宽松解析：空默认 PENDING，非法值给出可修正提示（工具会兜底降级）
     */
    private Status parseStatusLenient(String raw) {
        try {
            return Status.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.LEARNING_PLAN_STATUS_INVALID,
                "条目状态必须是 PENDING(待开始)/IN_PROGRESS(进行中)/DONE(已完成) 之一: " + raw);
        }
    }

    /**
     * REST 入参严格解析
     */
    private Status parseStatusStrict(String raw) {
        try {
            return Status.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.LEARNING_PLAN_STATUS_INVALID);
        }
    }

    private String normalizeTopic(String topic) {
        String trimmed = topic != null ? topic.trim() : "";
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "计划主题不能为空");
        }
        return trimmed.length() > TOPIC_MAX_LENGTH ? trimmed.substring(0, TOPIC_MAX_LENGTH) : trimmed;
    }

    private String normalizeGoal(String goal) {
        String trimmed = goal != null ? goal.trim() : "";
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > GOAL_MAX_LENGTH ? trimmed.substring(0, GOAL_MAX_LENGTH) : trimmed;
    }
}

package interview.guide.modules.learning.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.LearningRecordMapper;
import interview.guide.modules.learning.model.LearningRecordDTO.CreateRecordRequest;
import interview.guide.modules.learning.model.LearningRecordDTO.LearningRecordResponse;
import interview.guide.modules.learning.model.LearningRecordDTO.UpdateRecordRequest;
import interview.guide.modules.learning.model.LearningRecordEntity;
import interview.guide.modules.learning.model.LearningRecordEntity.Mastery;
import interview.guide.modules.learning.repository.LearningRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 学习台账服务
 * Agent 自动提炼与手动维护共用同一套读写逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningRecordService {

    private static final int TOPIC_MAX_LENGTH = 200;

    private final LearningRecordRepository recordRepository;
    private final LearningRecordMapper recordMapper;

    /**
     * 台账列表（keyword 同时匹配主题与摘要，大小写不敏感）
     */
    public List<LearningRecordResponse> list(Long userId, String keyword) {
        return recordMapper.toResponseList(listEntities(userId, keyword));
    }

    /**
     * 台账实体列表（Agent 工具与 system prompt 注入用）
     */
    public List<LearningRecordEntity> listEntities(Long userId, String keyword) {
        List<LearningRecordEntity> records = recordRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        if (keyword == null || keyword.isBlank()) {
            return records;
        }

        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        return records.stream()
            .filter(r -> contains(r.getTopic(), needle) || contains(r.getSummary(), needle))
            .toList();
    }

    /**
     * 最近学到的知识点（注入 system prompt 用）
     */
    public List<LearningRecordEntity> recentTopics(Long userId, int limit) {
        return recordRepository.findByUserIdOrderByUpdatedAtDesc(userId)
            .stream()
            .limit(limit)
            .toList();
    }

    /**
     * Agent 上报知识点：同名主题（忽略大小写）更新，否则新建
     */
    @Transactional
    public UpsertResult upsertFromAgent(Long userId, String topic, String summary,
                                        String mastery, Long sourceSessionId) {
        Mastery parsedMastery = Mastery.parse(mastery);
        String cleanTopic = normalizeTopic(topic);
        String cleanSummary = summary != null ? summary.trim() : "";

        LearningRecordEntity existing = recordRepository
            .findByUserIdAndTopicIgnoreCase(userId, cleanTopic)
            .orElse(null);
        boolean created = existing == null;

        LearningRecordEntity record = existing != null ? existing : new LearningRecordEntity();
        if (created) {
            record.setUserId(userId);
            record.setTopic(cleanTopic);
        }
        record.setSummary(cleanSummary);
        record.setMastery(parsedMastery);
        record.setSourceSessionId(sourceSessionId);
        record.setLastReviewedAt(LocalDateTime.now());
        record = recordRepository.save(record);

        log.info("Agent 更新学习台账: userId={}, topic={}, mastery={}, created={}",
            userId, cleanTopic, parsedMastery, created);
        return new UpsertResult(record, created);
    }

    /**
     * upsert 结果：record 为落库后的实体，created 标识本次是否新建
     */
    public record UpsertResult(LearningRecordEntity record, boolean created) {
    }

    /**
     * 手动新增
     */
    @Transactional
    public LearningRecordResponse create(Long userId, CreateRecordRequest request) {
        String cleanTopic = normalizeTopic(request.topic());
        recordRepository.findByUserIdAndTopicIgnoreCase(userId, cleanTopic)
            .ifPresent(existing -> {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "知识点「" + cleanTopic + "」已存在");
            });

        LearningRecordEntity entity = new LearningRecordEntity();
        entity.setUserId(userId);
        entity.setTopic(cleanTopic);
        entity.setSummary(request.summary().trim());
        entity.setMastery(Mastery.parse(request.mastery()));
        entity.setLastReviewedAt(LocalDateTime.now());
        entity = recordRepository.save(entity);

        log.info("手动新增学习记录: userId={}, topic={}", userId, cleanTopic);
        return recordMapper.toResponse(entity);
    }

    /**
     * 手动更新（null 字段不修改）
     */
    @Transactional
    public LearningRecordResponse update(Long userId, Long id, UpdateRecordRequest request) {
        LearningRecordEntity entity = getOwnedRecord(userId, id);

        if (request.topic() != null && !request.topic().isBlank()) {
            entity.setTopic(normalizeTopic(request.topic()));
        }
        if (request.summary() != null && !request.summary().isBlank()) {
            entity.setSummary(request.summary().trim());
        }
        if (request.mastery() != null) {
            entity.setMastery(Mastery.parse(request.mastery()));
        }
        return recordMapper.toResponse(recordRepository.save(entity));
    }

    /**
     * 删除（仅本人的记录）
     */
    @Transactional
    public void delete(Long userId, Long id) {
        getOwnedRecord(userId, id);
        recordRepository.deleteById(id);
        log.info("删除学习记录: userId={}, id={}", userId, id);
    }

    private LearningRecordEntity getOwnedRecord(Long userId, Long id) {
        LearningRecordEntity entity = recordRepository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.LEARNING_RECORD_NOT_FOUND));
        if (!entity.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.LEARNING_RECORD_NOT_FOUND);
        }
        return entity;
    }

    private String normalizeTopic(String topic) {
        String trimmed = topic != null ? topic.trim() : "";
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识点主题不能为空");
        }
        return trimmed.length() > TOPIC_MAX_LENGTH ? trimmed.substring(0, TOPIC_MAX_LENGTH) : trimmed;
    }

    private boolean contains(String value, String lowerNeedle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }
}
